![Scala CI](https://github.com/ScalaConsultants/mesmer-akka-agent/workflows/Scala%20CI/badge.svg)

# Mesmer Akka Agent

Mesmer Akka Agent is an [OpenTelemetry](https://opentelemetry.io/) instrumentation library for [Akka](https://akka.io/) applications. 

Currently supports the following Akka modules and metrics:

### Akka core

- Running actors
- Mailbox size
- Stash size
- Mailbox time
- Processed messages
- Processing time
- Sent messages

### Akka Cluster

- Shards per region
- Reachable nodes
- Unreachable nodes
- Entities per region
- Shard regions on node
- Entities on node
- Nodes down

### Akka HTTP

- Connections
- Requests
- Responses
- Responses 2xx
- Responses 3xx
- Responses 4xx
- Responses 5xx
- Response time 
- Response time 2xx
- Response time 3xx
- Response time 4xx
- Response time 5xx
- Endpoint responses
- Endpoint responses 2xx 
- Endpoint responses 3xx 
- Endpoint responses 4xx 
- Endpoint responses 5xx 
- Endpoint response time 2xx
- Endpoint response time 3xx
- Endpoint response time 4xx
- Endpoint response time 5xx

### Akka Persistence

- Persisted events
- Event persistence time
- Recovery total
- Recovery time
- Snapshots

### Akka Streams

- Running streams
- Running operators per stream
- Running operators
- Stream throughput
- Operator throughput
- Operator processing time

## Getting started

//TODO

# Architecture 

See [overview](https://github.com/ScalaConsultants/mesmer-akka-agent/blob/main/extension_overview.png).

//TODO

# Local testing

`example` subproject contains a test application that uses Akka Cluster sharding with Mesmer Akka Agent extension. Go [here](example/README.md) for more information.

# General remarks

In general, I liked code and I wouldn't complain if I had to work on it. However, there are some issues I found: 

* Many tests are written in a bad way. I pointed few things in the code but in general those are repeated issues for many tests:
    * They are hard to read (consider using /given/when/then pattern)
    * They are overcomplicated with long setup (consider creating builders to simplify /given part)
    * It's hard to guess intent of the test because many generators are used so I cannot check quicly what the expected 
      value should be
      
* Core module seems to be an anti-pattern (as a base for each module). As I commented in the code, it might be good to 
  consider making agent module dependence-free to reduce coupling
  
* There's a lot of code which seems to be not in use. You might want to scan codebase for it

* I'm not sure if I read code correctly but I cannot see timeouts set for telemetry client

* I don't like packaging, especially in extension module. Examples:
    * `io.scalac.core.actor` is duplicated
    * util/model/services packages suggest that you place classes based by classes function, not by theirs subdomains 