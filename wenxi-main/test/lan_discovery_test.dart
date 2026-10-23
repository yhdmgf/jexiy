import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:asterlink/core/json.dart';
import 'package:asterlink/data/state_store.dart';
import 'package:asterlink/lan/lan_datagrams.dart';
import 'package:asterlink/lan/lan_files.dart';
import 'package:asterlink/lan/lan_models.dart';
import 'package:asterlink/lan/lan_service.dart';
import 'support.dart';

void main() {
  test(
    'UDP discovery exchanges only device identity and the current HTTP port',
    () async {
      final local = LanService(
        store: StateStore.memory(),
        files: LanFiles(android: false),
        requestedPort: 0,
        requestedDiscoveryPort: 0,
        bindAddress: InternetAddress.loopbackIPv4,
        addresses: () async => ['127.0.0.1'],
      );
      final probe = await RawDatagramSocket.bind(
        InternetAddress.loopbackIPv4,
        0,
      );
      final outgoing = LanDatagrams(probe);
      final reply = Completer<Json>();
      probe.listen((event) {
        if (event == RawSocketEvent.write) {
          outgoing.flush();
          return;
        }
        if (event != RawSocketEvent.read) return;
        final packet = probe.receive();
        if (packet != null && !reply.isCompleted) {
          reply.complete(asJson(jsonDecode(utf8.decode(packet.data))));
        }
      });
      try {
        await local.start();
        expect(local.discoveryPort, greaterThan(0));
        final peerId = lanId();
        void send(Json data) => outgoing.send(
          utf8.encode(jsonEncode(data)),
          InternetAddress.loopbackIPv4,
          local.discoveryPort,
        );
        send({
          'protocol': lanProtocol,
          'id': peerId,
          'name': '测试手机',
          'port': 32123,
          'discover': true,
        });
        final announced = await reply.future.timeout(
          const Duration(seconds: 6),
        );
        expect(announced.keys.toSet(), {
          'protocol',
          'id',
          'name',
          'port',
          'discover',
        });
        expect(announced.str('protocol'), lanProtocol);
        expect(announced.integer('port'), local.port);
        expect(announced.str('id'), local.id);
        expect(local.peers.single.id, peerId);
        expect(local.peers.single.port, 32123);
        send({
          'protocol': 'not-compatible',
          'id': lanId(),
          'name': 'unexpected',
          'port': 1,
        });
        send({
          'protocol': lanProtocol,
          'id': peerId,
          'name': '手机改名',
          'port': 32123,
        });
        await until(() => local.peers.single.name == '手机改名');
        expect(local.peers.length, 1);
        await local.stop();
        expect(local.discoveryPort, 0);
        expect(local.peers, isEmpty);
      } finally {
        outgoing.close();
        probe.close();
        await local.close();
      }
    },
  );
}
