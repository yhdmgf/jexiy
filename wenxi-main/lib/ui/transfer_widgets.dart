import 'package:flutter/material.dart';
import 'common.dart';

class TransferFilterBar extends StatelessWidget {
  const TransferFilterBar({
    super.key,
    required this.entries,
    required this.selected,
    required this.onSelected,
    required this.accent,
    required this.keyPrefix,
  });
  final List<(String, int)> entries;
  final int selected;
  final ValueChanged<int>? onSelected;
  final Color accent;
  final String keyPrefix;

  @override
  Widget build(BuildContext context) => SingleChildScrollView(
    scrollDirection: Axis.horizontal,
    padding: const EdgeInsets.symmetric(horizontal: 16),
    child: Row(
      children: [
        for (var i = 0; i < entries.length; i++)
          Padding(
            padding: const EdgeInsets.only(right: 18),
            child: Semantics(
              selected: selected == i,
              button: true,
              child: InkWell(
                key: ValueKey('$keyPrefix-$i'),
                onTap: onSelected == null ? null : () => onSelected!(i),
                borderRadius: BorderRadius.circular(6),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Padding(
                      padding: const EdgeInsets.symmetric(vertical: 11),
                      child: Text(
                        i == 0
                            ? entries[i].$1
                            : '${entries[i].$1} ${entries[i].$2}',
                        style: TextStyle(
                          fontSize: 12,
                          fontWeight: selected == i
                              ? FontWeight.w600
                              : FontWeight.w400,
                          color: selected == i ? accent : secondary(context),
                        ),
                      ),
                    ),
                    AnimatedContainer(
                      duration: const Duration(milliseconds: 150),
                      height: 2,
                      width: 18,
                      decoration: BoxDecoration(
                        color: selected == i ? accent : Colors.transparent,
                        borderRadius: BorderRadius.circular(2),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
      ],
    ),
  );
}

class TransferActionButton extends StatelessWidget {
  const TransferActionButton({
    super.key,
    required this.icon,
    required this.label,
    required this.onPressed,
    required this.color,
  });
  final IconData icon;
  final String label;
  final VoidCallback? onPressed;
  final Color color;

  @override
  Widget build(BuildContext context) => IconButton(
    tooltip: label,
    onPressed: onPressed,
    padding: const EdgeInsets.all(4),
    constraints: const BoxConstraints(minWidth: 40, minHeight: 40),
    icon: Container(
      width: 32,
      height: 32,
      decoration: BoxDecoration(
        color: color.withValues(alpha: onPressed == null ? .04 : .09),
        borderRadius: BorderRadius.circular(11),
      ),
      child: Icon(
        icon,
        size: 18,
        color: onPressed == null ? secondary(context) : color,
      ),
    ),
  );
}
