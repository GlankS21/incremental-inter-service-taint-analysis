# channel-extractor

Static extraction of the inter-service channel model of a Spring microservice system: the first
stage of the incremental inter-service taint analysis (the per-service taint summaries are
composed along these channels).

It reads sources only (no build, no classpath) and produces JSON with:

| Section       | Content                                                                               |
|---------------|---------------------------------------------------------------------------------------|
| `endpoints`   | Spring MVC handlers: HTTP method, path, parameter sources, exposure through the gateway |
| `httpClients` | OpenFeign client methods and resolved `RestTemplate` requests (`clientKind` `FEIGN` / `REST_TEMPLATE`) with the places that call them |
| `publishers`  | `RabbitTemplate` sends and transactional-outbox enqueues: exchange, routing key, payload type |
| `bindings`    | queue ↔ exchange bindings (`BindingBuilder`, `new Binding`, `@QueueBinding`)           |
| `deadLetters` | queues declared with `x-dead-letter-exchange` (`QueueBuilder.deadLetterExchange`/`withArgument`, an arguments `Map`) |
| `listeners`   | `@RabbitListener` / `@RabbitHandler` methods and their payload parameter             |
| `channels`    | resolved edges: `HTTP` client → endpoint (attribute `clientKind`), `AMQP` publisher → listener (`viaDeadLetter` when routed through a dead-letter exchange) |
| `unresolved`  | what was seen but could not be linked, with the reason                                |

Values are resolved through constants, string concatenation, local variables, helper methods
(also in shared libraries) and the arguments of every caller of a private helper, so a queue is
only paired with the routing keys passed together with it.

`RestTemplate` requests are resolved from the URL argument: unresolvable parts (`+ id`) become
`{?}` path segments, `http://<host>[:port]/<path>` gives the target, and the host is matched to a
service by `spring.application.name` or by module directory name. The HTTP method comes from the
method name (`getForObject`, `postForEntity`, `put`, `delete`, `patchForObject`, ...) or from the
`HttpMethod` argument of `exchange`/`execute`. Local variables assigned in branches yield one
client per value; a call inside a private helper yields one client per caller.

A message published to a TTL queue with a dead-letter exchange is followed to the queues that
exchange routes it to (with `x-dead-letter-routing-key`, or the original key when it is not set),
up to four hops.

## Usage

```bash
./mvnw package
java -jar target/channel-extractor-0.1.0-SNAPSHOT.jar ../../ecommerce/backend --out model.json
```

`--gateway-config <file>` sets the Spring Cloud Gateway routes; by default the first
`api-gateway.yml` under the root is used.

## Not modelled yet

- `RestClient`, `WebClient`: listed as `HTTP_CALL`
- `RestTemplate` requests whose base URL comes from configuration (`@Value`, properties), whose
  `HttpMethod` is a variable, or that are sent as a `RequestEntity`: listed as `HTTP_CALL` with
  the reason; `UriComponentsBuilder` URLs are not evaluated
- a `{?}` path segment of a `RestTemplate` URL matches any segment, also literal ones
  (`/order/{?}` can link to `/order/tickets`)
- dead-letter queues declared in shared-library helpers; dead-lettering configured by broker
  policies instead of queue arguments
- gateway path rewriting filters (`StripPrefix`, `RewritePath`)
- queue names and keys from property placeholders (`${...}`)
- bindings declared inside shared-library helpers
