import 'package:flutter_test/flutter_test.dart';
import 'package:asterlink/core/json.dart';
import 'package:asterlink/data/http.dart';
import 'package:asterlink/data/providers/uc.dart';
import 'package:asterlink/domain/auth.dart';
import 'package:asterlink/domain/models.dart';
import 'support.dart';
import 'uc_tv_support.dart';

const _noticeUrl = 'https://dl.uc.cn/replacement.mp4';
const _notice = HttpResult(206, 'x', {
  'content-range': ['bytes 0-0/15340287'],
  'content-length': ['1'],
});

HttpResult _guestLink() => HttpResult(
  200,
  jsonResponse({
    'status': 200,
    'code': 0,
    'data': [
      {'fid': ucTvVideo.id, 'size': ucTvVideo.size, 'download_url': _noticeUrl},
    ],
  }).body,
  {
    'set-cookie': ['__pugs=guest-only; Path=/'],
  },
);

UcTvFixture _fixture({bool authorized = false}) {
  final f = UcTvFixture(credential: ucTvCredential(authorized: authorized));
  f.repository.connectors[CloudPlatform.uc] = UcConnector(
    f.http,
    store: f.vault,
    now: () => f.now,
    taskDelay: Duration.zero,
    directShareDownloadEnabled: (authenticated) => !authenticated,
    stageCleanup: (cleanup) async {
      f.staged.add(cleanup);
      await f.cleanups.stage(cleanup);
    },
  );
  f.respond = (request) {
    if (request.uri.path.endsWith('/file/download') &&
        request.json.containsKey('pwd_id')) {
      expect(request.headers['Cookie'], isEmpty);
      return _guestLink();
    }
    if (request.uri.host == 'dl.uc.cn') {
      expect(request.headers['Cookie'], '__pugs=guest-only');
      return _notice;
    }
    return null;
  };
  return f;
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  for (final error in ['network', '503']) {
    test('UC guest $error falls back to the selected account', () async {
      final f = _fixture();
      final first = f.respond!;
      f.respond = (r) {
        if (r.uri.path.endsWith('/file/download') &&
            r.json.containsKey('pwd_id')) {
          if (error == 'network') {
            throw const HttpRequestFailure(
              'network unavailable',
              kind: 'network',
              retryable: false,
            );
          }
          return const HttpResult(503, 'unavailable');
        }
        return first(r);
      };
      final spec = await f.repository.prepare(ucTvShare, ucTvVideo);
      expect(spec.url, 'https://media.example/original.mp4');
      expect(spec.guestDownload, isFalse);
      expect(spec.headers['Cookie'], f.credential.primary);
      expect(f.calls('/1/clouddrive/share/sharepage/save'), hasLength(1));
    });
  }

  test(
    'UC guest replacement asks for login before any TV authorization',
    () async {
      final http = FakeHttp(
        (request) => request.method == 'POST' ? _guestLink() : _notice,
      );
      final connector = UcConnector(http);
      await expectLater(
        connector.download(ucTvShare, ucTvVideo, null),
        throwsA(
          isA<AccountLoginRequired>()
              .having((error) => error.message, 'action', contains('请登录'))
              .having(
                (error) => error.message,
                'no grant yet',
                isNot(contains('TV')),
              )
              .having(
                (error) => error.message,
                'no token retry performed',
                isNot(contains('已刷新分享凭证重试')),
              ),
        ),
      );
      expect(http.calls, hasLength(2));
    },
  );

  test(
    'UC guest replacement falls back to the selected account original',
    () async {
      final f = _fixture();
      final owner = f.credential;
      final spec = await f.repository.prepare(ucTvShare, ucTvVideo);
      expect(spec.url, 'https://media.example/original.mp4');
      expect(spec.expectedSize, ucTvVideo.size);
      expect(spec.guestDownload, isFalse);
      expect(spec.cleanup, same(f.staged.single));
      expect(spec.headers['Cookie'], owner.primary);
      expect(spec.headers['Cookie'], isNot(contains('__pugs')));
      expect(f.credential.sameAs(owner), isTrue);
      expect(f.calls('/1/clouddrive/file/download'), hasLength(2));
      final fallback = f.calls('/1/clouddrive/file/download').last;
      expect(fallback.json['fids'], ['saved-video']);
      expect(fallback.json.containsKey('pwd_id'), isFalse);
      expect(f.calls('/1/clouddrive/share/sharepage/save'), hasLength(1));
      expect(f.calls('/oauth/authorize'), isEmpty);
      expect(f.calls('/ucdrive/token'), isEmpty);
    },
  );

  test(
    'UC guest replacement still uses an existing authorized original grant',
    () async {
      final f = _fixture(authorized: true);
      final spec = await f.repository.prepare(ucTvShare, ucTvVideo);
      expect(spec.url, 'https://media.example/original.mp4');
      expect(spec.expectedSize, ucTvVideo.size);
      expect(spec.guestDownload, isFalse);
      expect(f.calls('/1/clouddrive/file/download'), hasLength(1));
      expect(f.calls('/file').single.uri.queryParameters['method'], 'download');
      expect(f.calls('/oauth/authorize'), isEmpty);
    },
  );

  test(
    'UC account replacement still requires authorization and stages cleanup',
    () async {
      final f = _fixture();
      final first = f.respond!;
      f.respond = (request) {
        if (request.uri.path.endsWith('/file/download') &&
            !request.json.containsKey('pwd_id')) {
          return jsonResponse({
            'status': 200,
            'code': 0,
            'data': [
              {
                'fid': 'saved-video',
                'file_name': ucTvVideo.name,
                'size': ucTvVideo.size,
                'md5': 'ffc6341c056de41cf4ab95343d9c22fe',
                'download_url': _noticeUrl,
              },
            ],
          });
        }
        if (request.uri.host == 'dl.uc.cn') return _notice;
        return first(request);
      };
      await expectLater(
        f.repository.prepare(ucTvShare, ucTvVideo),
        throwsA(isA<UcTvAuthorizationRequired>()),
      );
      expect(f.calls('/1/clouddrive/file/download'), hasLength(2));
      expect(f.staged, hasLength(1));
      expect(f.cleanups.pendingCount, 1);
      expect(f.calls('/file'), isEmpty);
      expect(f.calls('/oauth/authorize'), isEmpty);
      expect(f.calls('/ucdrive/token'), isEmpty);
    },
  );

  test(
    'Cancellation during the guest check prevents any account transfer',
    () async {
      final f = _fixture();
      final first = f.respond!;
      final scope = RequestScope();
      f.respond = (request) {
        if (request.uri.host == 'dl.uc.cn') scope.cancel();
        return first(request);
      };
      await expectLater(
        scope.run(() => f.repository.prepare(ucTvShare, ucTvVideo)),
        throwsA(
          isA<AppException>().having(
            (e) => e.message,
            'cancelled',
            contains('取消'),
          ),
        ),
      );
      expect(f.calls('/1/clouddrive/file'), isEmpty);
      expect(f.calls('/1/clouddrive/share/sharepage/save'), isEmpty);
      expect(f.staged, isEmpty);
    },
  );
}
