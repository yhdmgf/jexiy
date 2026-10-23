import 'models.dart';

class CloudListPreferences {
  CloudListPreferences(Object? value, Iterable<CloudPlatform> defaults) {
    final available = {
      for (final platform in defaults) platform.name: platform,
    };
    final data = value is Map ? value : const {};
    final savedOrder = data['order'];
    final savedHidden = data['hidden'];
    order = [
      ...{
        if (savedOrder is List)
          for (final name in savedOrder)
            ?available[name],
        ...available.values,
      },
    ];
    hidden = {
      if (savedHidden is List)
        for (final name in savedHidden)
          ?available[name],
    };
  }

  late final List<CloudPlatform> order;
  late final Set<CloudPlatform> hidden;

  Map<String, Object> toJson() => {
    'order': [for (final platform in order) platform.name],
    'hidden': [for (final platform in hidden) platform.name],
  };
}
