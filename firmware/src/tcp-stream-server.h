// Single-client TCP stream server on proto::TCP_PORT.
// Session: accept -> TCP_NODELAY -> ring.reset() -> 8-byte header -> pop+write frames
// until the client disconnects or a write fails, then back to idle. Prints a stats line
// every 5 s. Runs entirely from loop(); the capture task only reads hasClient().
#pragma once

#include "audio-ring-buffer.h"

namespace stream {

void begin();

// True while a client session is active (read from the capture task).
bool hasClient();

// Accept / pump frames / detect disconnect. Call from loop() as often as possible.
void service(AudioRingBuffer& ring);

}  // namespace stream
