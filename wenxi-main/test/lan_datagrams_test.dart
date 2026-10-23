import 'dart:convert';
import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:asterlink/lan/lan_datagrams.dart';

class _BusyDatagramSocket extends Fake implements RawDatagramSocket {
  bool blocked = true;
  String? failingAddress;
  final sent = <({String address, int port, String value})>[];
  @override
  bool writeEventsEnabled = false;
  @override
  int send(List<int> buffer, InternetAddress address, int port) {
    if (failingAddress == address.address) {
      throw const SocketException('Interface unavailable');
    }
    if (blocked) return 0;
    sent.add((
      address: address.address,
      port: port,
      value: utf8.decode(buffer),
    ));
    return buffer.length;
  }
}

void main() {
  test(
    'A busy UDP socket keeps the latest announcement and sends every target when writable',
    () {
      final socket = _BusyDatagramSocket();
      final sender = LanDatagrams(socket);
      sender.send(
        utf8.encode('旧设备名称'),
        InternetAddress('192.168.1.255'),
        52380,
      );
      sender.send(utf8.encode('组播公告'), InternetAddress('239.255.77.81'), 52380);
      sender.send(
        utf8.encode('新设备名称'),
        InternetAddress('192.168.1.255'),
        52380,
      );
      expect(socket.sent, isEmpty);
      expect(socket.writeEventsEnabled, isTrue);
      socket.blocked = false;
      sender.flush();
      expect(socket.sent.map((packet) => packet.value).toList(), [
        '新设备名称',
        '组播公告',
      ]);
      expect(socket.writeEventsEnabled, isFalse);
      sender.flush();
      expect(socket.sent, hasLength(2));
    },
  );

  test(
    'Disconnected interfaces do not prevent delivery to a working interface',
    () {
      final socket = _BusyDatagramSocket();
      final sender = LanDatagrams(socket);
      sender.send([65], InternetAddress('192.168.1.255'), 52380);
      sender.send([66], InternetAddress('10.0.0.255'), 52380);
      socket.failingAddress = '192.168.1.255';
      socket.blocked = false;
      sender.flush();
      expect(socket.sent.single.value, 'B');
    },
  );

  test(
    'Discovery buffering stays bounded and stops retrying when the service closes',
    () {
      final socket = _BusyDatagramSocket();
      final sender = LanDatagrams(socket);
      for (var i = 1; i <= 200; i++) {
        sender.send([65], InternetAddress('10.0.0.$i'), 52380);
      }
      socket.blocked = false;
      sender.flush();
      expect(socket.sent.length, 128);
      socket.blocked = true;
      sender.send([66], InternetAddress('10.0.0.1'), 52380);
      sender.close();
      socket.blocked = false;
      sender.flush();
      sender.send([67], InternetAddress('10.0.0.1'), 52380);
      expect(socket.sent.length, 128);
    },
  );
}
