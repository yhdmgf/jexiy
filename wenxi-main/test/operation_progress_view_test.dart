import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:asterlink/core/operation_progress.dart';
import 'package:asterlink/ui/operation_progress_view.dart';

void main() {
  for (final compact in [false, true]) {
    for (final desktop in [false, true]) {
      testWidgets(
        'file read progress wraps on ${desktop ? 'desktop' : 'phone'} with compact=$compact',
        (tester) async {
          tester.view.devicePixelRatio = 1;
          tester.view.physicalSize = desktop
              ? const Size(1280, 800)
              : const Size(320, 640);
          addTearDown(tester.view.resetPhysicalSize);
          addTearDown(tester.view.resetDevicePixelRatio);
          final progress = OperationProgress();
          addTearDown(progress.dispose);
          final release = Completer<void>();
          late Zone readZone;
          final work = progress.run(
            () => OperationProgress.step(OperationStage.readFiles, () async {
              readZone = Zone.current;
              OperationProgress.reportFiles(5200);
              await release.future;
            }),
          );
          await tester.pumpWidget(
            MaterialApp(
              home: MediaQuery(
                data: MediaQueryData(
                  size: desktop ? const Size(1280, 800) : const Size(320, 640),
                  textScaler: TextScaler.linear(desktop ? 1 : 1.8),
                ),
                child: Scaffold(
                  body: Center(
                    child: SizedBox(
                      width: desktop ? 480 : 280,
                      child: OperationProgressView(
                        progress: progress,
                        label: '正在打开文件夹',
                        compact: compact,
                      ),
                    ),
                  ),
                ),
              ),
            ),
          );
          expect(find.text('正在读取文件列表… · 已读取 5200 项'), findsOneWidget);
          readZone.run(
            () => OperationProgress.reportFiles(5200, waitingForRetry: true),
          );
          await tester.pump();
          expect(find.text('请求较频繁，正在等待重试… · 已读取 5200 项'), findsOneWidget);
          expect(tester.takeException(), isNull);
          release.complete();
          await work;
          await tester.pump();
          expect(find.text('读取文件列表 · 完成 · 已读取 5200 项'), findsOneWidget);
          expect(tester.takeException(), isNull);
          await tester.pumpWidget(const SizedBox.shrink());
        },
      );
    }
  }
}
