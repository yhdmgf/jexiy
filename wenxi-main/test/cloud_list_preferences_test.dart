import 'package:asterlink/domain/cloud_list_preferences.dart';
import 'package:asterlink/domain/models.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('saved order removes duplicates and appends newly supported clouds', () {
    final preferences = CloudListPreferences(
      {
        'order': ['uc', 'retired', 'uc'],
        'hidden': ['quark', 'retired'],
      },
      [CloudPlatform.quark, CloudPlatform.uc, CloudPlatform.lanzou],
    );
    expect(preferences.order, [
      CloudPlatform.uc,
      CloudPlatform.quark,
      CloudPlatform.lanzou,
    ]);
    expect(preferences.hidden, {CloudPlatform.quark});
    final restored = CloudListPreferences(
      preferences.toJson(),
      preferences.order,
    );
    expect(restored.order, preferences.order);
    expect(restored.hidden, preferences.hidden);
  });

  test('malformed settings use defaults', () {
    final preferences = CloudListPreferences(
      {'order': false, 'hidden': 12},
      [CloudPlatform.uc],
    );
    expect(preferences.order, [CloudPlatform.uc]);
    expect(preferences.hidden, isEmpty);
  });
}
