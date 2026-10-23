import 'dart:math' as math;
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import '../domain/playback.dart';
import '../playback/playback_controller.dart';
import '../playback/playlist_order.dart';
import 'player_theme.dart';

class PlayerPlaylistPanel extends StatefulWidget {
  const PlayerPlaylistPanel({
    super.key,
    required this.controller,
    required this.onSelect,
    required this.onOrder,
  });
  final PlaybackController controller;
  final ValueChanged<int> onSelect;
  final ValueChanged<PlaybackPlaylistOrder> onOrder;
  @override
  State<PlayerPlaylistPanel> createState() => _PlayerPlaylistPanelState();
}

class _PlayerPlaylistPanelState extends State<PlayerPlaylistPanel> {
  final _search = TextEditingController();
  final _scroll = ScrollController();
  List<int>? _order;
  List<int> _shown = [];
  String _query = '';
  bool _centerPending = true;
  double _extent = 80;

  void _filter() {
    _order = widget.controller.playlistIndices;
    _shown = _order!
        .where(
          (index) => widget.controller.entries[index].name
              .toLowerCase()
              .contains(_query),
        )
        .toList();
  }

  void _centerCurrent() {
    if (!_scroll.hasClients) return;
    final at = _shown.indexOf(widget.controller.index);
    if (at < 0) return;
    _scroll.jumpTo(
      (at * _extent - (_scroll.position.viewportDimension - _extent) / 2).clamp(
        0,
        _scroll.position.maxScrollExtent,
      ),
    );
  }

  @override
  void dispose() {
    _search.dispose();
    _scroll.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final controller = widget.controller;
    _extent =
        80 + math.max(0, MediaQuery.textScalerOf(context).scale(14) - 14) * 3.2;
    if (!identical(_order, controller.playlistIndices)) {
      _filter();
      _centerPending = true;
    }
    if (_centerPending) {
      _centerPending = false;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) _centerCurrent();
      });
    }
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 4, 16, 8),
          child: TextField(
            key: const Key('player-episode-search'),
            controller: _search,
            textInputAction: TextInputAction.search,
            decoration: InputDecoration(
              hintText: '搜索视频名称或集数',
              isDense: true,
              prefixIcon: const Icon(CupertinoIcons.search, size: 18),
              suffixIcon: _query.isEmpty
                  ? null
                  : IconButton(
                      tooltip: '清空搜索',
                      icon: const Icon(
                        CupertinoIcons.xmark_circle_fill,
                        size: 18,
                      ),
                      onPressed: () => setState(() {
                        _search.clear();
                        _query = '';
                        _filter();
                        _centerPending = true;
                      }),
                    ),
              filled: true,
              fillColor: Colors.white.withValues(alpha: .06),
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(12),
                borderSide: BorderSide.none,
              ),
            ),
            onChanged: (value) => setState(() {
              _query = value.trim().toLowerCase();
              _filter();
              if (_scroll.hasClients) _scroll.jumpTo(0);
            }),
          ),
        ),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16),
          child: Row(
            children: [
              Expanded(
                child: DropdownButtonHideUnderline(
                  child: DropdownButton<PlaybackPlaylistOrder>(
                    key: const Key('player-episode-order'),
                    value: controller.playlistOrder,
                    isExpanded: true,
                    dropdownColor: playerPanelColor,
                    style: TextStyle(color: Colors.white, fontSize: 13),
                    items: [
                      for (final order in PlaybackPlaylistOrder.values)
                        DropdownMenuItem(
                          value: order,
                          child: Text(playlistOrderLabel(order)),
                        ),
                    ],
                    onChanged: controller.loading
                        ? null
                        : (value) {
                            if (value != null) widget.onOrder(value);
                          },
                  ),
                ),
              ),
              const SizedBox(width: 12),
              Text(
                '${_shown.length}/${controller.entries.length}',
                style: const TextStyle(fontSize: 12, color: Colors.white54),
              ),
              IconButton(
                key: const Key('player-episode-current'),
                tooltip: '定位当前集',
                icon: const Icon(CupertinoIcons.location, size: 20),
                onPressed: () => setState(() {
                  _search.clear();
                  _query = '';
                  _filter();
                  _centerPending = true;
                }),
              ),
            ],
          ),
        ),
        const Divider(height: 1),
        if (_shown.isEmpty)
          const Padding(
            padding: EdgeInsets.all(32),
            child: Text('没有匹配的视频', style: TextStyle(color: Colors.white54)),
          )
        else
          Expanded(
            child: Scrollbar(
              controller: _scroll,
              child: ListView.builder(
                key: const Key('player-episode-list'),
                controller: _scroll,
                itemExtent: _extent,
                itemCount: _shown.length,
                itemBuilder: (context, row) {
                  final index = _shown[row], entry = controller.entries[index];
                  final current = index == controller.index;
                  final mark = controller.history.bookmark(entry.key);
                  final live =
                      current &&
                      controller.ready &&
                      controller.state.duration > Duration.zero;
                  final duration = live
                      ? controller.state.duration
                      : mark?.duration ?? Duration.zero;
                  final position = live
                      ? controller.state.position
                      : mark?.position ?? Duration.zero;
                  final completed = live
                      ? controller.state.completed
                      : mark?.completed == true;
                  final fraction = duration > Duration.zero
                      ? (position.inMilliseconds / duration.inMilliseconds)
                            .clamp(0.0, 1.0)
                      : 0.0;
                  final progress = completed
                      ? '已看完'
                      : position > Duration.zero
                      ? '已看 ${playbackTime(position)} / ${playbackTime(duration)}'
                      : '未观看';
                  return Material(
                    color: current
                        ? playerAccent(context).withValues(alpha: .1)
                        : Colors.transparent,
                    child: InkWell(
                      key: ValueKey('episode-$index'),
                      onTap: () => widget.onSelect(index),
                      child: Padding(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 16,
                          vertical: 8,
                        ),
                        child: Row(
                          children: [
                            SizedBox(
                              width: 28,
                              child: current
                                  ? Icon(
                                      CupertinoIcons.play_fill,
                                      color: playerAccent(context),
                                      size: 16,
                                    )
                                  : Text(
                                      '${controller.playlistPositionOf(index) + 1}',
                                      textAlign: TextAlign.center,
                                      style: const TextStyle(
                                        fontSize: 12,
                                        color: Colors.white38,
                                      ),
                                    ),
                            ),
                            const SizedBox(width: 12),
                            Expanded(
                              child: Column(
                                mainAxisAlignment: MainAxisAlignment.center,
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text(
                                    entry.name,
                                    maxLines: 2,
                                    overflow: TextOverflow.ellipsis,
                                    style: TextStyle(
                                      fontSize: 14,
                                      height: 1.2,
                                      color: current
                                          ? playerAccent(context)
                                          : Colors.white,
                                    ),
                                  ),
                                  const SizedBox(height: 4),
                                  Text(
                                    '${current ? '当前集 · ' : ''}$progress',
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    style: const TextStyle(
                                      fontSize: 11,
                                      height: 1.1,
                                      color: Colors.white54,
                                    ),
                                  ),
                                  const SizedBox(height: 5),
                                  ClipRRect(
                                    borderRadius: BorderRadius.circular(2),
                                    child: LinearProgressIndicator(
                                      value: completed ? 1 : fraction,
                                      minHeight: 2,
                                      color: current
                                          ? playerAccent(context)
                                          : Colors.white30,
                                      backgroundColor: Colors.white10,
                                    ),
                                  ),
                                ],
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  );
                },
              ),
            ),
          ),
      ],
    );
  }
}
