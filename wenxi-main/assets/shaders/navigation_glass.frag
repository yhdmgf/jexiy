#version 460 core
#include <flutter/runtime_effect.glsl>

uniform vec2 u_size;
uniform sampler2D u_texture;
out vec4 fragColor;

void main() {
  vec2 p = FlutterFragCoord().xy;
  vec2 halfSize = u_size * 0.5;
  float radius = halfSize.y;
  vec2 q = p - halfSize;
  vec2 center = vec2(clamp(q.x, -halfSize.x + radius, halfSize.x - radius), 0.0);
  vec2 delta = q - center;
  float distanceToEdge = radius - length(delta);
  float rim = 1.0 - smoothstep(0.0, min(16.0, radius), distanceToEdge);
  vec2 normal = delta / max(length(delta), 0.001);
  vec2 uv = (p - normal * rim * 5.0) / u_size;
  uv = clamp(uv, vec2(0.001), vec2(0.999));
#ifdef IMPELLER_TARGET_OPENGLES
  uv.y = 1.0 - uv.y;
#endif
  fragColor = texture(u_texture, uv);
}
