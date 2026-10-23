import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import '../app_services.dart';
import 'downloads_page.dart';
import 'common.dart';
import 'transfer_overlay_control.dart';
import 'uploads_page.dart';

class TransfersPage extends StatelessWidget {
  const TransfersPage(this.services, {super.key, this.active = true});
  final AppServices services;
  final bool active;
  @override
  Widget build(BuildContext context) => ValueListenableBuilder<int>(
    valueListenable: services.transferTab,
    builder: (context, tab, _) => Material(
      color: backgroundSurface(context, fallback: Colors.transparent),
      child: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 6, 12, 2),
            child: Row(
              children: [
                Expanded(
                  child: Align(
                    alignment: Alignment.centerLeft,
                    child: ConstrainedBox(
                      constraints: const BoxConstraints(maxWidth: 360),
                      child: SizedBox(
                        width: double.infinity,
                        child: CupertinoSlidingSegmentedControl<int>(
                          groupValue: tab,
                          backgroundColor: fill(context),
                          thumbColor: Theme.of(context).colorScheme.surface,
                          children: {
                            0: Padding(
                              padding: const EdgeInsets.symmetric(vertical: 6),
                              child: _tab(
                                context,
                                '下载',
                                CupertinoIcons.arrow_down,
                                tab == 0,
                                themeAccent(context),
                              ),
                            ),
                            1: Padding(
                              padding: const EdgeInsets.symmetric(vertical: 6),
                              child: _tab(
                                context,
                                '上传',
                                CupertinoIcons.arrow_up,
                                tab == 1,
                                themeCompanion(context),
                              ),
                            ),
                          },
                          onValueChanged: (value) {
                            if (value != null) {
                              services.transferTab.value = value;
                            }
                          },
                        ),
                      ),
                    ),
                  ),
                ),
                if (services.downloadOverlay.supported) ...[
                  const SizedBox(width: 10),
                  TransferOverlayControl(
                    services.downloadOverlay,
                    compact: true,
                  ),
                ],
              ],
            ),
          ),
          Expanded(
            child: IndexedStack(
              index: tab,
              children: [
                DownloadsPage(services, active: active && tab == 0),
                UploadsPage(services, active: active && tab == 1),
              ],
            ),
          ),
        ],
      ),
    ),
  );

  Widget _tab(
    BuildContext context,
    String label,
    IconData icon,
    bool selected,
    Color accent,
  ) => Row(
    mainAxisAlignment: MainAxisAlignment.center,
    children: [
      Icon(icon, size: 15, color: selected ? accent : secondary(context)),
      const SizedBox(width: 6),
      Text(
        label,
        style: TextStyle(
          fontSize: 13,
          fontWeight: selected ? FontWeight.w600 : FontWeight.w400,
          color: selected ? accent : secondary(context),
        ),
      ),
    ],
  );
}
