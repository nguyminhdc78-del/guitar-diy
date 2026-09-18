#!/usr/bin/env python3
"""Desktop reference receiver for the uMIC stream protocol v1 (stdlib only).

Joins nothing itself: connect your PC to WiFi "uMIC" first, then run
    python tools/stream-to-wav.py --seconds 30 --out capture.wav

Protocol (little-endian): header "uMIC" + u32 sample_rate, then 1928-byte frames of
u32 seq | u32 timestamp_us | 960 x int16 PCM. Forward seq gaps are filled with silence
so the WAV timeline stays equal to wall-clock time even when the ESP32 dropped frames.
This script is the protocol oracle for the Android app (StreamProtocol / FrameGapTracker).
"""
import argparse
import math
import socket
import struct
import sys
import time
import wave

MAGIC = b"uMIC"
HEADER_BYTES = 8
FRAME_SAMPLES = 960
FRAME_PCM_BYTES = FRAME_SAMPLES * 2
FRAME_BYTES = 8 + FRAME_PCM_BYTES
SILENCE = bytes(FRAME_PCM_BYTES)


def recv_exact(sock, n):
    """Read exactly n bytes or raise ConnectionError on EOF."""
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("socket closed by peer")
        buf.extend(chunk)
    return bytes(buf)


def rms_dbfs(pcm):
    samples = struct.unpack("<%dh" % (len(pcm) // 2), pcm)
    if not samples:
        return -96.0
    rms = math.sqrt(sum(s * s for s in samples) / len(samples))
    return max(-96.0, 20 * math.log10(rms / 32768.0)) if rms > 0 else -96.0


def main():
    ap = argparse.ArgumentParser(description="Receive uMIC stream and write a WAV file")
    ap.add_argument("--host", default="192.168.4.1")
    ap.add_argument("--port", type=int, default=5000)
    ap.add_argument("--out", default="capture.wav")
    ap.add_argument("--seconds", type=float, default=0, help="stop after N seconds (0 = until Ctrl+C)")
    args = ap.parse_args()

    sock = socket.create_connection((args.host, args.port), timeout=5)
    sock.settimeout(5)
    header = recv_exact(sock, HEADER_BYTES)
    if header[:4] != MAGIC:
        print("bad magic: %r (expected %r)" % (header[:4], MAGIC), file=sys.stderr)
        return 1
    sample_rate = struct.unpack("<I", header[4:8])[0]
    print("connected to %s:%d, magic OK, sample_rate=%d" % (args.host, args.port, sample_rate))

    wav = wave.open(args.out, "wb")
    wav.setnchannels(1)
    wav.setsampwidth(2)
    wav.setframerate(sample_rate)

    expected = None          # next expected seq
    frames = drops = 0
    last_ts = None
    ts_delta_ms = 0.0
    bytes_in_window = 0
    window_pcm = bytearray()
    t0 = t_report = time.monotonic()
    try:
        while True:
            buf = recv_exact(sock, FRAME_BYTES)
            seq, ts_us = struct.unpack("<II", buf[:8])
            pcm = buf[8:]
            if expected is not None:
                gap = seq - expected
                if gap < 0:
                    continue                      # stale/duplicate frame: ignore
                if gap > 0:
                    wav.writeframes(SILENCE * gap)   # keep timeline == wall clock
                    drops += gap
            expected = seq + 1
            if last_ts is not None:
                ts_delta_ms = ((ts_us - last_ts) & 0xFFFFFFFF) / 1000.0
            last_ts = ts_us
            wav.writeframes(pcm)
            frames += 1
            bytes_in_window += FRAME_BYTES
            window_pcm.extend(pcm)

            now = time.monotonic()
            if now - t_report >= 1.0:
                kbps = bytes_in_window * 8 / (now - t_report) / 1000.0
                print("t=%.1fs frames=%d drops=%d rms=%.1fdBFS kbps=%.0f ts_delta_ms=%.1f"
                      % (now - t0, frames, drops, rms_dbfs(bytes(window_pcm)), kbps, ts_delta_ms))
                t_report = now
                bytes_in_window = 0
                window_pcm.clear()
            if args.seconds and now - t0 >= args.seconds:
                break
    except KeyboardInterrupt:
        print("\nstopped by user")
    except (ConnectionError, socket.timeout) as e:
        print("connection ended: %s" % e, file=sys.stderr)
    finally:
        sock.close()
        wav.close()
    total_s = (frames + drops) * FRAME_SAMPLES / float(sample_rate)
    print("wrote %s: %.2f s, %d frames received, %d frames dropped (filled with silence)"
          % (args.out, total_s, frames, drops))
    return 0


if __name__ == "__main__":
    sys.exit(main())
