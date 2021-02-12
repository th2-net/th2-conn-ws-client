# WebSocket Client v0.0.1

This microservice allows sending and receiving messages via WebSocket protocol

## Configuration

The main configuration is done by changing following properties:

+ **uri** - connection URI
+ **frameType** - outgoing WebSocket frame type, can be either `TEXT` or `BINARY` (`TEXT` by default)
+ **sessionAlias** - session alias for incoming/outgoing th2 messages (e.g. `ws_api`)
+ **handlerSettings** - WebSocket event handler settings

### Event handler configuration

Event handler can be configured by changing following properties in the `handlerSettings` block of the main configuration:

+ **pingInterval** - interval for sending ping-messages in ms (`30000` by default)

### Configuration example

```yaml
uri: wss://echo.websocket.org
frameType: TEXT
sessionAlias: api_session
handlerSettings:
  pingInterval: 30000
```

### MQ pins

* input queue with `subscribe` and `send` attributes for outgoing messages
* output queue with `publish`, `first` (for incoming messages) or `second` (for outgoing messages) attributes

## Inputs/outputs

This section describes the messages received and produced by the service

### Inputs

This service receives messages that will be sent via MQ as `MessageGroup`s, containing a single `RawMessage` with a message body

### Outputs

Incoming and outgoing messages are sent via MQ as `MessageGroup`s, containing a single `RawMessage` with a message body.

## Deployment via `infra-mgr`

Here's an example of `infra-mgr` config required to deploy this service

```yaml
apiVersion: th2.exactpro.com/v1
kind: Th2Box
metadata:
  name: ws-client
spec:
  image-name: ghcr.io/th2-net/th2-conn-ws-client
  image-version: 0.0.1
  custom-config:
    uri: wss://echo.websocket.org
    sessionAlias: api_session
    handlerSettings:
      pingInterval: 30000
  type: th2-conn
  pins:
    - name: to_send
      connection-type: mq
      attributes:
        - subscribe
        - send
    - name: outgoing_messages
      connection-type: mq
      attributes:
        - publish
        - second
        - raw
    - name: incoming_messages
      connection-type: mq
      attributes:
        - publish
        - first
        - raw 
```
