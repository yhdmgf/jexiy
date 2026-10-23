import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../domain/playback.dart';
import '../playback/playback_sleep_timer.dart';
import 'player_theme.dart';

String playerSleepLabel(PlaybackSleepTimer timer) => switch (timer.mode) {
  PlaybackSleepMode.off => timer.stopped ? '已停止播放' : '未开启',
  PlaybackSleepMode.episode => '本集播放完后停止',
  PlaybackSleepMode.duration =>
    '${playbackTime(timer.remaining ?? Duration.zero)} 后停止',
};

class PlayerSleepPanel extends StatefulWidget {
  const PlayerSleepPanel({super.key, required this.timer});
  final PlaybackSleepTimer timer;
  @override
  State<PlayerSleepPanel> createState() => _PlayerSleepPanelState();
}

class _PlayerSleepPanelState extends State<PlayerSleepPanel> {
  final _minutes = TextEditingController();
  String? _error;
  @override
  void dispose() {
    _minutes.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: widget.timer,
    builder: (context, _) => ListView(
      shrinkWrap: true,
      padding: const EdgeInsets.fromLTRB(20, 4, 20, 24),
      children: [
        Container(
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            color: playerAccent(context).withValues(alpha: .09),
            borderRadius: BorderRadius.circular(14),
          ),
          child: Row(
            children: [
              Icon(
                CupertinoIcons.moon_zzz,
                color: playerAccent(context),
                size: 26,
              ),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      playerSleepLabel(widget.timer),
                      key: const Key('player-sleep-status'),
                      style: const TextStyle(fontWeight: FontWeight.w600),
                    ),
                    const SizedBox(height: 4),
                    const Text(
                      '仅本次内置播放有效，停止时保留进度',
                      style: TextStyle(
                        fontSize: 12,
                        color: Colors.white54,
                        height: 1.5,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 18),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            for (final minutes in [15, 30, 60])
              ActionChip(
                key: ValueKey('player-sleep-$minutes'),
                label: Text('$minutes 分钟'),
                onPressed: () =>
                    widget.timer.schedule(Duration(minutes: minutes)),
              ),
          ],
        ),
        ListTile(
          contentPadding: EdgeInsets.zero,
          title: const Text('本集结束时停止'),
          subtitle: const Text(
            '播完当前视频，不再自动播放下一集',
            style: TextStyle(fontSize: 12, color: Colors.white54),
          ),
          key: const Key('player-sleep-episode'),
          trailing: Icon(
            widget.timer.mode == PlaybackSleepMode.episode
                ? CupertinoIcons.check_mark_circled_solid
                : CupertinoIcons.circle,
            color: widget.timer.mode == PlaybackSleepMode.episode
                ? playerAccent(context)
                : Colors.white38,
          ),
          onTap: widget.timer.afterEpisode,
        ),
        const Divider(),
        const SizedBox(height: 12),
        TextField(
          key: const Key('player-sleep-minutes'),
          controller: _minutes,
          keyboardType: TextInputType.number,
          inputFormatters: [
            FilteringTextInputFormatter.digitsOnly,
            LengthLimitingTextInputFormatter(3),
          ],
          decoration: InputDecoration(
            labelText: '自定义时长',
            suffixText: '分钟',
            hintText: '1–720',
            errorText: _error,
            border: OutlineInputBorder(borderRadius: BorderRadius.circular(10)),
          ),
        ),
        const SizedBox(height: 12),
        FilledButton(
          key: const Key('player-sleep-apply'),
          onPressed: () {
            final minutes = int.tryParse(_minutes.text);
            if (minutes == null || minutes < 1 || minutes > 720) {
              setState(() => _error = '请输入 1 至 720 分钟');
              return;
            }
            setState(() => _error = null);
            FocusScope.of(context).unfocus();
            widget.timer.schedule(Duration(minutes: minutes));
          },
          child: const Text('开始计时'),
        ),
        if (widget.timer.mode != PlaybackSleepMode.off) ...[
          const SizedBox(height: 8),
          OutlinedButton(
            key: const Key('player-sleep-cancel'),
            onPressed: widget.timer.cancel,
            child: const Text('取消定时停止'),
          ),
        ],
      ],
    ),
  );
}
