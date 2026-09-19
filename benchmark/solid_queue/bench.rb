# Copyright 2024 Joaquín Díez Gómez
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Solid Queue counterpart of the Kool Queue benchmark, so the two can be
# compared on the same machine and the same database with the same method.
# The knobs (BENCH_JOBS, BENCH_REPS, BENCH_SLEEP_MS, BENCH_THREADS) mirror the
# Kotlin side one for one. See docs/benchmark.md.
#
#   gem install solid_queue pg
#   cd benchmark/solid_queue && BENCH_JOBS=300 ruby bench.rb

require "logger"
require "rails"
require "active_record/railtie"
require "active_job/railtie"

# Solid Queue ships a Rails::Engine, so a host application has to exist before
# it can be required. This is the smallest one that boots.
class BenchApp < Rails::Application
  config.eager_load = false
  config.logger = ActiveSupport::Logger.new(IO::NULL)
end

require "solid_queue"
BenchApp.initialize!

N        = (ENV["BENCH_JOBS"]      || 300).to_i
REPS     = (ENV["BENCH_REPS"]      || 3).to_i
SLEEP_MS = (ENV["BENCH_SLEEP_MS"]  || 0).to_i
THREADS  = (ENV["BENCH_THREADS"]   || 5).to_i
LABEL    = ENV["BENCH_LABEL"] || "solid_queue"

ActiveRecord::Base.logger = ActiveSupport::Logger.new(IO::NULL)
ActiveJob::Base.logger    = ActiveSupport::Logger.new(IO::NULL)
SolidQueue.logger         = ActiveSupport::Logger.new(IO::NULL)

# Connection comes from config/database.yml (Rails insists on it existing).
conn = ActiveRecord::Base.connection
conn.execute("DROP SCHEMA IF EXISTS solid_bench CASCADE")
conn.execute("CREATE SCHEMA solid_bench")
conn.execute("SET search_path TO solid_bench")

schema = Gem.loaded_specs["solid_queue"].gem_dir +
         "/lib/generators/solid_queue/install/templates/db/queue_schema.rb"
ActiveRecord::Schema.verbose = false
load schema

ActiveJob::Base.queue_adapter = :solid_queue

$count = 0
$lock  = Mutex.new

class BenchJob < ActiveJob::Base
  queue_as :bench
  def perform(_payload)
    sleep(SLEEP_MS / 1000.0) if SLEEP_MS > 0
    $lock.synchronize { $count += 1 }
  end
end

worker = SolidQueue::Worker.new(queues: "bench", threads: THREADS, polling_interval: 0.1)
worker.start

# Warm up the poller so its first-poll cost is not billed to rep 1.
BenchJob.perform_later("warmup")
t = Time.now
sleep 0.01 while $count < 1 && (Time.now - t) < 60

rates = []
REPS.times do |rep|
  $lock.synchronize { $count = 0 }
  t0 = Time.now
  N.times { |i| BenchJob.perform_later("bench-#{rep}-#{i}") }
  loop do
    break if $lock.synchronize { $count } >= N
    raise "timed out at #{$count}/#{N}" if (Time.now - t0) > 600
    sleep 0.002
  end
  total_ms = ((Time.now - t0) * 1000).round

  sleep 1
  final = $lock.synchronize { $count }
  raise "expected exactly #{N}, got #{final} (double-processing?)" unless final == N

  rate = N * 1000.0 / total_ms
  rates << rate
  puts "BENCHRUN label=#{LABEL} rep=#{rep + 1} jobs=#{N} total_ms=#{total_ms} e2e_per_sec=#{'%.1f' % rate}"
end

mean = rates.sum / rates.size
sd   = rates.size < 2 ? 0.0 : Math.sqrt(rates.map { |r| (r - mean)**2 }.sum / (rates.size - 1))
puts "BENCHSTAT label=#{LABEL} jobs=#{N} reps=#{REPS} e2e mean=#{'%.1f' % mean} sd=#{'%.1f' % sd} min=#{'%.1f' % rates.min} max=#{'%.1f' % rates.max}"

worker.stop
