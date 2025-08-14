# KLog - Clojure Logging Library Documentation

## Table of Contents
1. [Overview](#overview)
2. [Installation](#installation)
3. [Quick Start](#quick-start)
4. [Core Concepts](#core-concepts)
5. [Configuration](#configuration)
6. [Appenders](#appenders)
7. [Log Levels](#log-levels)
8. [Thread-Local Context](#thread-local-context)
9. [Integrations](#integrations)
10. [Monitoring](#monitoring)
11. [API Reference](#api-reference)
12. [Testing](#testing)

## Overview

KLog is a flexible, high-performance logging library for Clojure applications designed with distributed systems in mind. It provides structured logging with JSON output, multiple appender types, and integrations with popular logging services like Elasticsearch, Datadog, and Grafana Loki.

### Key Features

- **Structured Logging**: All logs are structured as Clojure maps and serialized to JSON
- **Asynchronous Processing**: Uses Clojure agents for non-blocking log processing
- **Multiple Appenders**: Support for stdout, file, Elasticsearch, Datadog, Loki, and custom appenders
- **Thread-Local Context**: Maintain request-specific context across log calls
- **Log Levels**: Standard log levels (trace, debug, info, warn, error, fatal)
- **JVM Monitoring**: Built-in JVM metrics collection
- **Graceful Shutdown**: Automatic flushing of logs on application shutdown

## Installation

Add the following dependency to your `deps.edn`:

```clojure
{:deps {klog/klog {:local/root "path/to/klog"}}}
```

Dependencies required:
- `cheshire/cheshire`: JSON serialization
- `http-kit/http-kit`: HTTP client for remote appenders
- `org.clojure/java.jdbc`: Database support for devlog appender
- `com.taoensso/nippy`: Binary serialization for obscure appender

## Quick Start

```clojure
(require '[klog.core :as log])

;; Add a stdout appender with pretty formatting
(log/stdout-pretty-appender)

;; Log a simple message
(log/log :user/login {:user-id 123 :msg "User logged in"})

;; Log with different levels
(log/info :api/request {:endpoint "/users" :method :get})
(log/error :db/connection {:msg "Connection failed" :retry 3})
(log/debug :cache/hit {:key "user:123" :ttl 3600})
```

## Core Concepts

### Log Structure

Every log entry is a Clojure map with the following automatic fields:

- `ts`: ISO-8601 timestamp
- `timeUnix`: Unix timestamp in milliseconds
- `w`: Thread name
- `ev`: Event name (keyword)
- `lvl`: Log level (defaults to :info)
- `st/ns`: Source namespace
- `st/fn`: Source function
- `st/line`: Source line number

Additional fields can be added through:
- Direct log data
- Thread-local context
- Appender transformations

### Event-Driven Logging

KLog uses an event-driven approach where each log entry has an event name (`:ev` field):

```clojure
(log/log :user/registered {:user-id 123 :email "user@example.com"})
(log/log :payment/processed {:amount 99.99 :currency "USD"})
(log/log :cache/miss {:key "product:456"})
```

## Configuration

### Enabling/Disabling Logging

```clojure
;; Disable logging globally
(log/disable-log)

;; Enable logging globally
(log/enable-log)

;; Check if logging is enabled
log/*enable*  ;; => true/false
```

Logging can also be disabled via environment variable:
```bash
KLOG_DISABLE=true
```

## Appenders

Appenders determine where and how logs are output. Multiple appenders can be active simultaneously.

### Built-in Appenders

#### Stdout Appender

```clojure
;; JSON output to stdout
(log/stdout-appender)
(log/stdout-appender :info)  ; With log level filter

;; Pretty formatted output
(log/stdout-pretty-appender)
(log/stdout-pretty-appender :debug)

;; Google Cloud Logging format
(log/stdout-google-appender)

;; Filtered pretty output (regex-based)
(log/stdout-pretty-appender-regexp #"user")
```

#### File Appender

```clojure
;; Write to file with rotation
(log/file-appender "/var/log/app.log" 10000)  ; Rotate after 10000 lines
```

#### Elasticsearch Appender

```clojure
(log/es-appender
 {:es-url "http://localhost:9200"
  :es-auth ["user" "password"]  ; Optional
  :index-pat "'app-logs'-yyyy-MM-dd"
  :batch-size 200
  :batch-timeout 60000  ; milliseconds
  :transform (fn [log] (assoc log :app "myapp"))})  ; Optional transformer
```

#### Datadog Appender

```clojure
(log/dd-appender
 {:dd-auth "YOUR_API_KEY"
  :dd-site "datadoghq.com"  ; or "datadoghq.eu", etc.
  :dd-tags "env:prod,service:api"
  :batch-size 100
  :batch-timeout 30000
  :fallback-file "/var/log/dd-fallback.log"})  ; Optional fallback
```

#### Loki Appender

```clojure
(log/loki-appender
 {:url "http://localhost:3100"
  :stream {:app "myapp" :env "prod"}
  :batch-size 200
  :batch-timeout 3600000})
```

#### Database Appender (DevLog)

```clojure
(log/devlog-appender
 {:classname "org.postgresql.Driver"
  :subprotocol "postgresql"
  :subname "//localhost:5432/logs"
  :user "logger"
  :password "secret"})
```

#### Obscure Appender (TCP Binary)

```clojure
(log/obscure-appender "tcp://logserver:7777")
```

### Custom Appenders

Create custom appenders using `add-appender`:

```clojure
;; Simple custom appender
(log/add-appender :my-appender :info
  (fn [log-map]
    (println "Custom:" log-map)))

;; Appender with state
(log/add-appender
 {:id :stateful-appender
  :f (fn [state log-map]
       (swap! state update :count inc))
  :state (atom {:count 0})
  :flush (fn [state] (println "Flushing with" @state))
  :transform (fn [log] (assoc log :transformed true))})
```

### Managing Appenders

```clojure
;; Remove an appender
(log/rm-appender :stdout)

;; Clear all appenders
(log/clear-appenders)

;; List current appenders
@log/appenders  ; => {:stdout {...}, :file {...}}
```

## Log Levels

KLog supports standard log levels with numeric priorities:

- `:trace` (600)
- `:debug` (500)
- `:info` (400)
- `:warn` (300)
- `:error` (200)
- `:fatal` (100)
- `:off` (0)
- `:all` (Integer/MAX_VALUE)

### Using Log Levels

```clojure
;; Helper functions for each level
(log/trace :detailed/operation {:step 1})
(log/debug :cache/lookup {:key "user:123"})
(log/info :request/received {:path "/api/users"})
(log/warn :rate/limit {:requests 1000})
(log/error :db/connection {:retry 3})

;; Set level on appender
(log/add-appender :production :warn 
  (fn [log] (println log)))  ; Only warn and above
```

## Thread-Local Context

KLog provides thread-local storage for maintaining context across log calls within the same thread/request:

### Operation Context (`-op`)

```clojure
(log/set-op "GET /users")
(log/log :db/query {:sql "SELECT * FROM users"})
;; Log includes :op "GET /users"
(log/clear-op)
```

### Request Context (`-ctx`)

```clojure
(log/set-ctx "req-123-456")
(log/log :processing {:step "validation"})
;; Log includes :ctx "req-123-456"
(log/clear-ctx)
```

### Tenant Context (`-tn`)

```clojure
(log/set-tn "customer-xyz")
(log/log :data/access {:table "orders"})
;; Log includes :tn "customer-xyz"
(log/clear-tn)
```

### Generic Context Map

```clojure
(log/set-context {:user-id 123 :session "abc" :role "admin"})
(log/log :action/performed {:action "delete"})
;; Log includes all context fields
(log/clear-context)
```

## Integrations

### Web Request/Response Logging

KLog includes special handling for web events:

```clojure
;; Request logging
(log/log :w/req {:w_m :post
                  :w_url "/api/users"
                  :w_qs "filter=active"
                  :w_ip "192.168.1.1"
                  :w_user_agent "Mozilla/5.0..."})

;; Response logging
(log/log :w/resp {:w_st 200
                   :d 150  ; duration in ms
                   :w_referer "https://example.com"})

;; Exception in web context
(log/log :w/ex {:msg "Internal error"
                 :etr (with-out-str (stacktrace/print-stack-trace e))})
```

### Database Operations

```clojure
(log/log :db/q {:sql "SELECT * FROM users WHERE id = ?"
                 :db_prm [123]
                 :d 45})

(log/log :db/ex {:sql "INSERT INTO logs ..."
                  :msg "Constraint violation"})
```

### Resource Operations

```clojure
(log/log :resource/create {:rtp "Patient" :rid "123"})
(log/log :resource/update {:rtp "Encounter" :rid "456"})
(log/log :resource/delete {:rtp "Observation" :rid "789"})
```

## Monitoring

### JVM Metrics

KLog includes a JVM monitoring module that collects metrics:

```clojure
(require '[klog.monitor :as monitor])

;; Start monitoring (logs every 60 seconds)
(monitor/start 60000)

;; Metrics logged include:
;; - Memory usage (heap, non-heap, pools)
;; - Thread counts
;; - Garbage collection stats
;; - System load average

;; Stop monitoring
(monitor/stop)
```

Example JVM log entry:
```clojure
{:ev :jvm
 :mem_total 512.5   ; MB
 :mem_free 128.3    ; MB
 :mem_used 384.2    ; MB
 :load_avg 2.1
 :threads 42
 :gc {:count 0.5 :time 12.3}  ; per second
 :mem_pool {:metaspace {:used 45.2 :committed 48.0}
            :g1-eden-space {:used 100.0 :committed 200.0 :heap true}}}
```

## API Reference

### Core Functions

#### `(log ev data)`
Main logging function. 
- `ev`: Event keyword
- `data`: Map of log data

#### `(log-ex exception)`
Log an exception with stack trace.

#### `(exception ev exception [data])`
Log an exception with custom event and optional data.

#### `(flush [timeout-ms])`
Flush all pending logs. Returns status for each appender.

#### `(enable-log)` / `(disable-log)`
Enable or disable logging globally.

### Appender Management

#### `(add-appender id level-or-fn [state])`
Add an appender with optional state.

#### `(rm-appender id)`
Remove an appender by ID.

#### `(clear-appenders)`
Remove all appenders.

### Context Management

#### `(set-ctx value)` / `(get-ctx)` / `(clear-ctx)`
Manage request context ID.

#### `(set-op value)` / `(get-op)` / `(clear-op)`
Manage operation context.

#### `(set-tn value)` / `(clear-tn)`
Manage tenant context.

#### `(set-context map)` / `(get-context)` / `(clear-context)`
Manage generic context map.

### Utilities

#### `(source-line)`
Get current source location (ns, function, line).

#### `(format-date date)`
Format date to ISO-8601 string.

## Testing

KLog includes comprehensive tests demonstrating usage:

```clojure
;; Test utilities
(use-fixtures :each
  (fn [test-fn]
    ;; Save and restore appenders
    (let [original-appenders @log/appenders]
      (log/clear-appenders)
      (test-fn)
      (reset! log/appenders original-appenders))))

;; Capture logs in tests
(def test-logs (atom []))
(log/add-appender :test (fn [log] (swap! test-logs conj log)))

;; Test your logging
(log/info :test/event {:data "value"})
(is (= 1 (count @test-logs)))
```

## Performance Considerations

1. **Asynchronous Processing**: Logs are processed asynchronously via agents, minimizing impact on application performance.

2. **Batching**: Remote appenders (ES, Datadog, Loki) batch logs to reduce network overhead.

3. **Automatic Flushing**: Logs are automatically flushed on JVM shutdown.

4. **Error Recovery**: Appenders continue functioning even if individual log processing fails.

5. **Lazy Evaluation**: Be careful with lazy sequences in log data - they're evaluated during JSON serialization.

## Best Practices

1. **Use Structured Events**: Define meaningful event names that describe what happened.
   ```clojure
   ;; Good
   (log/log :user/login-succeeded {:user-id 123})
   
   ;; Less descriptive
   (log/log :event {:msg "User 123 logged in"})
   ```

2. **Set Context Early**: Set thread-local context at request boundaries.
   ```clojure
   (defn handle-request [req]
     (log/set-ctx (get-request-id req))
     (try
       (process-request req)
       (finally
         (log/clear-ctx))))
   ```

3. **Use Appropriate Levels**: Reserve `:error` for actual errors, use `:warn` for concerning but handled situations.

4. **Include Relevant Data**: Add enough context to debug issues but avoid sensitive data.
   ```clojure
   (log/error :payment/failed 
     {:user-id 123 
      :amount 99.99
      ;; Don't log: :credit-card "..."
      :error-code "INSUFFICIENT_FUNDS"})
   ```

5. **Monitor Log Volume**: Use batching and appropriate log levels in production.

6. **Test Logging**: Include logging in your test scenarios to ensure critical events are logged.

## Troubleshooting

### Logs Not Appearing

1. Check if logging is enabled: `log/*enable*`
2. Verify appenders are configured: `@log/appenders`
3. Check log level filters on appenders
4. Call `(log/flush)` to force processing

### High Memory Usage

1. Reduce batch sizes for remote appenders
2. Implement log rotation for file appenders
3. Use appropriate log levels to reduce volume

### Lost Logs on Shutdown

Logs are automatically flushed on shutdown, but you can manually flush:
```clojure
(log/flush 5000)  ; Wait up to 5 seconds
```

## License

This library is proprietary to HealthSamurai.