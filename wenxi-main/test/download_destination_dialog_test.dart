import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:asterlink/ui/download_destination_dialog.dart';

void main() {
  for (final selection in [
    ('仅新任务使用', DownloadDestinationChoice.newTasks),
    ('同步未完成任务', DownloadDestinationChoice.unfinished),
    ('取消', null),
  ]) {
    testWidgets('directory choice: ${selection.$1}', (tester) async {
      DownloadDestinationChoice? choice;
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: Builder(
              builder: (context) => TextButton(
                onPressed: () async {
                  choice = await chooseDownloadDestinationScope(
                    context,
                    destination: r'D:\下载文件',
                    unfinished: 2274,
                  );
                },
                child: const Text('目录'),
              ),
            ),
          ),
        ),
      );
      await tester.tap(find.text('目录'));
      await tester.pumpAndSettle();
      expect(find.textContaining('2274'), findsOneWidget);
      expect(find.textContaining('保留断点'), findsOneWidget);
      await tester.tap(find.text(selection.$1));
      await tester.pumpAndSettle();
      expect(choice, selection.$2);
      expect(find.byType(SimpleDialog), findsNothing);
      expect(tester.takeException(), isNull);
    });
  }
}
