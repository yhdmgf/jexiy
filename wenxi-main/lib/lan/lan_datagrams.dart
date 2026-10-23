import 'dart:io';

/// Keeps discovery packets until the socket accepts them. UDP delivery itself
/// remains best effort; the service heartbeat repeats discovery announcements.
class LanDatagrams {
  LanDatagrams(this.socket) {
    socket.writeEventsEnabled = false;
  }

  final RawDatagramSocket socket;
  final _pending =
      <(String, int), ({List<int> bytes, InternetAddress address, int port})>{};
  bool _closed = false;

  void send(List<int> bytes, InternetAddress address, int port) {
    if (_closed) return;
    final key = (address.address, port);
    if (_pending.length >= 128 && !_pending.containsKey(key)) return;
    _pending[key] = (
      bytes: List.unmodifiable(bytes),
      address: address,
      port: port,
    );
    flush();
  }

  void flush() {
    if (_closed) return;
    socket.writeEventsEnabled = false;
    while (_pending.isNotEmpty) {
      final key = _pending.keys.first;
      final packet = _pending[key]!;
      try {
        if (socket.send(packet.bytes, packet.address, packet.port) == 0) {
          socket.writeEventsEnabled = true;
          return;
        }
      } on SocketException {
        // A disconnected interface must not block announcements on other NICs.
      }
      _pending.remove(key);
    }
  }

  void close() {
    _closed = true;
    _pending.clear();
  }
}
