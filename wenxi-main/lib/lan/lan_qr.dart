import '../core/json.dart';
import 'lan_models.dart';

const _invalidQr = '请扫描文析助手的设备连接二维码或文件分享二维码';

sealed class LanQrTarget {
  const LanQrTarget();

  static LanQrTarget parse(String text) {
    require(text.length <= 2048, _invalidQr);
    final uri = Uri.tryParse(text.trim());
    require(uri != null, _invalidQr);
    if (uri!.scheme == 'wenxi') {
      require(
        uri.host == 'lan' &&
            uri.path == '/connect' &&
            uri.userInfo.isEmpty &&
            !uri.hasPort &&
            !uri.hasFragment,
        _invalidQr,
      );
      final params = uri.queryParametersAll;
      require(
        params.length == 4 &&
            const [
              'v',
              'address',
              'id',
              'code',
            ].every((key) => params[key]?.length == 1),
        _invalidQr,
      );
      require(params['v']!.single == '1', '连接二维码版本不受支持，请更新文析助手');
      return LanConnectionQr(
        address: lanAddress(params['address']!.single),
        deviceId: params['id']!.single,
        code: params['code']!.single,
      ).._validate();
    }
    require(uri.scheme == 'http', _invalidQr);
    final address = lanAddress(uri.toString());
    require(
      RegExp(r'^/s/[a-f0-9]{32}/?$').hasMatch(address.path) &&
          !address.hasQuery &&
          !address.hasFragment,
      _invalidQr,
    );
    return LanShareQr(address);
  }
}

class LanConnectionQr extends LanQrTarget {
  const LanConnectionQr({
    required this.address,
    required this.deviceId,
    required this.code,
  });

  final Uri address;
  final String deviceId, code;

  void _validate() {
    lanAddress(address.toString());
    require(
      (address.path.isEmpty || address.path == '/') &&
          !address.hasQuery &&
          !address.hasFragment &&
          RegExp(r'^[a-f0-9]{32}$').hasMatch(deviceId) &&
          RegExp(r'^[0-9]{6}$').hasMatch(code),
      '连接二维码无效，请在对方设备重新打开二维码',
    );
  }

  String encode() {
    _validate();
    return Uri(
      scheme: 'wenxi',
      host: 'lan',
      path: '/connect',
      queryParameters: {
        'v': '1',
        'address': address.toString(),
        'id': deviceId,
        'code': code,
      },
    ).toString();
  }
}

class LanShareQr extends LanQrTarget {
  const LanShareQr(this.address);
  final Uri address;
}
