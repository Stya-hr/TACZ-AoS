#version 150

uniform vec4 ColorModulator;
uniform float SmokeTime;
in vec2 smokeUV;
in vec4 vertexColor;
out vec4 fragColor;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}
float noise(vec2 p) {
    vec2 cell = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(cell), hash(cell + vec2(1.0, 0.0)), f.x),
               mix(hash(cell + vec2(0.0, 1.0)), hash(cell + vec2(1.0, 1.0)), f.x), f.y);
}
void main() {
    // Continuous coordinates across packet boundaries; no scrolling texture seam or random per-frame flicker.
    vec2 p = vec2(smokeUV.x, smokeUV.y * 1.7);
    float broad = noise(p * vec2(0.8, 1.0) + vec2(-SmokeTime * 0.25, SmokeTime * 0.3));
    float detail = noise(p * vec2(2.7, 2.0) + vec2(SmokeTime * 0.4, -SmokeTime * 0.2));
    float cloud = smoothstep(0.18, 0.82, broad * 0.7 + detail * 0.3);
    float y = smokeUV.y + (broad - 0.5) * 0.28;
    float softEdge = pow(max(0.0, 1.0 - y * y), 1.8);
    // Always fade at the geometry boundary, even when the noisy center shifts toward an edge.
    softEdge *= 1.0 - smoothstep(0.72, 1.0, abs(smokeUV.y));
    float alpha = vertexColor.a * softEdge * (0.25 + 0.75 * cloud) * ColorModulator.a;
    if (alpha < 0.0039) discard;
    fragColor = vec4(vertexColor.rgb * ColorModulator.rgb, alpha);
}
