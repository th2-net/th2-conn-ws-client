# WebSocket Client v0.4.0

This microservice allows sending and receiving messages via WebSocket protocol

## Configuration

The main configuration is done by changing following properties:

+ **uri** - connection URI
+ **frameType** - outgoing WebSocket frame type, can be either `TEXT` or `BINARY` (`TEXT` by default)
+ **sessionGroup** - session group for incoming/outgoing th2 messages (equal to session alias by default)
+ **sessionAlias** - session alias for incoming/outgoing th2 messages (e.g. `ws_api`)
+ **handlerSettings** - WebSocket event handler settings
+ **grpcStartControl** - enables start/stop control via [gRPC service](https://github.com/th2-net/th2-grpc-conn/blob/master/src/main/proto/th2_grpc_conn/conn.proto#L24) (`false` by default)
+ **autoStart** - start service automatically (`true` by default and if `startControl` is `false`)
+ **autoStopAfter** - stop after N seconds if the service was started automatically prior to send (`0` by default which means disabled)
+ **maxBatchSize** - max size of outgoing message batch (`100` by default)
+ **maxFlushTime** - max message batch flush time (`1000` by default)
+ **useTransport** - use th2 transport or protobuf protocol to publish incoming/outgoing messages (`true` by default)
+ **validateCertificates** - enables/disables server certificate validation (`true` by default)
+ **clientCertificate** - path to client X.509 certificate in PEM format (requires `certificatePrivateKey`, `null` by
  default)
+ **certificatePrivateKey** - path to client certificate RSA private key (PKCS8 encoded) in PEM format (`null` by
  default)

Service will also automatically connect prior to message send if it wasn't connected

### Event handler configuration

Event handler can be configured by changing following properties in the `handlerSettings` block of the main configuration:

+ **pingInterval** - interval for sending ping-messages in ms (`30000` by default)
+ **defaultHeaders** - map of headers and their values to add to the HTTP handshake request (the map is **empty** by default)

### Configuration example

```yaml
uri: wss://echo.websocket.org
frameType: TEXT
sessionAlias: api_session
startControl: true
autoStart: true
handlerSettings:
  pingInterval: 30000
  defaultHeaders:
    HeaderA:
      - value1
      - value2
    HeaderB:
      - value3
```

### MQ pins

* least one of `to_send_via_protobuf` with [`subscribe`, `send`, `raw`] attributes 
  or `to_send_via_transport` with [`subscribe`, `send`, `transport-group`] attributes pins is required, 
  it's mean that conn can consume messages via one or both protocols.
* `outgoing_messages_via_protobuf` with [`publish`, `raw`] pin are required when useTransport is `false`
* `outgoing_messages_via_transport` with [`publish`, `transport-group`] pin are required when useTransport is `true`

## Inputs/outputs

This section describes the messages received and produced by the service

### Inputs

This service receives messages that will be sent via MQ as `MessageGroup`s, containing a single `RawMessage` with a message body

### Outputs

Incoming and outgoing messages are sent via MQ as `MessageGroup`s, containing a single `RawMessage` with a message body.

## Events

This section describes the events that can be produced by the service. You can use that information to trigger some actions on certain events.

### Connected

When the service is connected it will publish the event with 
**name**=`Connected to: <url>` 
and **type**=`Info`

### Disconnected

When the service is disconnected for some reason it will publish the event with
**name**=`Disconnected from: <uri> - statusCode: <statusCode>, reason: <reason>`
and **type**=`Info`

## Deployment via `infra-mgr`

Here's an example of `infra-mgr` config required to deploy this service

```yaml
apiVersion: th2.exactpro.com/v1
kind: Th2Box
metadata:
  name: ws-client
spec:
  image-name: ghcr.io/th2-net/th2-conn-ws-client
  image-version: 0.3.1
  custom-config:
    uri: wss://echo.websocket.org
    sessionAlias: api_session
    grpcStartControl: true
    autoStart: true
    autoStopAfter: 300
    validateCertificates: true
    clientCertificate: /path/to/certificate
    certificatePrivateKey: /path/to/certificate/private/key
    handlerSettings:
      pingInterval: 30000
  type: th2-conn
  pins:
    - name: to_send_via_transport
      connection-type: mq
      attributes:
        - subscribe
        - send
        - transport-group
    - name: outgoing_messages_via_transport
      connection-type: mq
      attributes:
        - publish
        - transport-group
```

## Changelog

### v0.4.0

* added support for th2 transport protocol
* added settings to configure TLS certificate validation
* provided ability to specify Web Socket sub-protocols in the IHandler.preOpen method 

#### Updated:
* updated bom: `4.5.0-dev`
* updated common: `5.4.1-dev`
* updated grpc-conn: `0.1.0-dev`
* updated kotlin: `1.8.22`

#### Added:
* common-utils: `2.2.0-dev`
* conn-http-client: `2.1.0-dev`

### v0.3.1

#### Added:
* common lib update from 3.25.1 to 3.40.0
* bom lib update from 3.0.0 to 3.1.0

## Changelog

### v0.3.0

#### Added:
* opportunity to add query params to uri from handler

### v0.2.5

#### Added:

* reconnect if handling of event fails
* ability to stop client during reconnect loop

### v0.2.4

#### Changed:

* The common version is updated from 3.13.4 to 3.25.1
* Events are published to all pins that match the requested attributes

### v0.2.3

#### Fixed:

* running-flag wasn't reset if connection has failed during start

### v0.2.2

#### Added:

* cancel ping timer in case of close/error WS events

### v0.2.1

#### Fixed:

* deadlock when trying to send a message from `IHandler.onOpen`

### v0.2.0

#### Added:

* a new `preOpen` method that allows the handler to adjust some client's settings
* `DefaultHandler` has the parameter to specify default headers for HTTP handshake

### v0.1.1

#### Fixed:

* inverted `autoStart` setting behavior
* reconnect loop during socket availability check

### v0.1.0

#### Added:

* ability to start/stop via gRPC service
* auto-start/stop feature

### v0.0.2

#### Fixed:

* high idle CPU usage
