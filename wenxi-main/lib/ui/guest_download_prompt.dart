import 'dart:async';
import 'package:flutter/material.dart';
import '../app_services.dart';
import 'common.dart';

class GuestDownloadPrompt extends StatefulWidget {
  const GuestDownloadPrompt(this.services, {super.key});
  final AppServices services;

  @override
  State<GuestDownloadPrompt> createState() => _GuestDownloadPromptState();
}

class _GuestDownloadPromptState extends State<GuestDownloadPrompt>
    with WidgetsBindingObserver {
  DialogRoute<bool>? _route;
  bool _scheduled = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _listen(widget.services, true);
    _changed();
  }

  void _listen(AppServices services, bool add) {
    for (final source in [
      services.guestDownloadNotice,
      services.control,
      services.store,
    ]) {
      if (add) {
        source.addListener(_changed);
      } else {
        source.removeListener(_changed);
      }
    }
  }

  @override
  void didUpdateWidget(GuestDownloadPrompt oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.services != widget.services) {
      _listen(oldWidget.services, false);
      _removeRoute(defer: true);
      _listen(widget.services, true);
      _changed();
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) => _changed();

  void _changed() {
    if (!mounted || _scheduled) return;
    _scheduled = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _scheduled = false;
      if (mounted) unawaited(_sync());
    });
    WidgetsBinding.instance.ensureVisualUpdate();
  }

  void _removeRoute({bool defer = false}) {
    final route = _route;
    _route = null;
    void remove() {
      if (route?.isActive == true) route!.navigator?.removeRoute(route);
    }

    if (defer) {
      WidgetsBinding.instance.addPostFrameCallback((_) => remove());
    } else {
      remove();
    }
  }

  Future<void> _sync() async {
    final services = widget.services;
    final hidden = services.settings.hideGuestDownloadNotice;
    if (hidden && services.guestDownloadNotice.value) {
      services.guestDownloadNotice.value = false;
    }
    final lifecycle = WidgetsBinding.instance.lifecycleState;
    final canShow =
        (lifecycle == null || lifecycle == AppLifecycleState.resumed) &&
        services.control.requiredUpdate == null;
    if (!services.guestDownloadNotice.value || !canShow) {
      _removeRoute();
      return;
    }
    if (_route != null) return;
    var hide = false;
    final route = _route = DialogRoute<bool>(
      context: context,
      barrierDismissible: false,
      builder: (context) => StatefulBuilder(
        builder: (context, setState) => AlertDialog(
          key: const ValueKey('guest-download-notice'),
          scrollable: true,
          title: const Text('游客下载提示'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                '游客下载使用临时接口，不能保证稳定性和速度。如果遇到问题，可以尝试登录对应网盘账号后再下载。',
                style: TextStyle(fontSize: 14, height: 1.6),
              ),
              const SizedBox(height: 12),
              CheckboxListTile(
                key: const ValueKey('hide-guest-download-notice'),
                value: hide,
                onChanged: (value) => setState(() => hide = value ?? false),
                title: const Text('不再显示', style: TextStyle(fontSize: 13)),
                controlAffinity: ListTileControlAffinity.leading,
                contentPadding: EdgeInsets.zero,
                dense: true,
              ),
            ],
          ),
          actions: [
            FilledButton(
              onPressed: () => Navigator.pop(context, hide),
              child: const Text('知道了'),
            ),
          ],
        ),
      ),
    );
    final choice = await Navigator.of(context, rootNavigator: true).push(route);
    if (!mounted || _route != route || widget.services != services) return;
    // Keep this request pending when backgrounding or a mandatory update
    // interrupts the dialog. Normal dismissals acknowledge this batch only.
    services.guestDownloadNotice.value = false;
    if (choice == true) {
      try {
        await services.updateSettings({'hideGuestDownloadNotice': true});
      } catch (error) {
        if (mounted) message(context, errorText(error));
      }
    }
    if (_route == route) _route = null;
    _changed();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _listen(widget.services, false);
    // The navigator may itself be disposing; remove only a surviving route.
    _removeRoute(defer: true);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => const SizedBox.shrink();
}
