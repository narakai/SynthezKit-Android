package com.leon.synthez.view

/**
 * GLSL ES 3.00 ports of the Metal shaders.
 *
 * Every texture keeps the image's top row in memory row 0. Offscreen passes
 * address texels through `gl_FragCoord`, which counts memory rows; the final
 * pass flips onto the default framebuffer through `vUV`, which is (0, 0) at
 * the top left of the viewport.
 */
internal object SZShaders {
    const val VERTEX = """#version 300 es
out vec2 vUV;
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
    vUV = vec2(p.x, 1.0 - p.y);
}
"""

    private const val HEADER = """#version 300 es
precision highp float;
precision highp int;
precision highp usampler2D;
out vec4 fragColor;

vec3 toLinear(vec3 c) {
    return mix(pow((c + 0.055) / 1.055, vec3(2.4)), c / 12.92, lessThanEqual(c, vec3(0.04045)));
}

vec3 toSrgb(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    return mix(1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, c * 12.92, lessThanEqual(c, vec3(0.0031308)));
}
"""

    /** Sharp bilinear: blend only within the device pixel that crosses a virtual pixel edge. */
    const val PLAIN = HEADER + """
in vec2 vUV;
uniform usampler2D uIndices;
uniform sampler2D uPalette;
uniform vec2 uSize;
uniform float uScale;

vec4 colorAt(ivec2 p) {
    return texelFetch(uPalette, ivec2(int(texelFetch(uIndices, p, 0).r), 0), 0);
}

void main() {
    vec2 v = vUV * uSize - 0.5;
    vec2 base = floor(v);
    vec2 t = clamp((v - base - 0.5) * uScale + 0.5, 0.0, 1.0);
    ivec2 last = ivec2(uSize) - 1;
    ivec2 c0 = clamp(ivec2(base), ivec2(0), last);
    ivec2 c1 = clamp(ivec2(base) + 1, ivec2(0), last);
    vec4 a = colorAt(ivec2(c0.x, c0.y));
    vec4 b = colorAt(ivec2(c1.x, c0.y));
    vec4 c = colorAt(ivec2(c0.x, c1.y));
    vec4 d = colorAt(ivec2(c1.x, c1.y));
    fragColor = vec4(mix(mix(a, b, t.x), mix(c, d, t.x), t.y).rgb, 1.0);
}
"""

    /** Each phosphor shows the new frame or its own fading glow, whichever is brighter. */
    const val PHOSPHOR = HEADER + """
uniform usampler2D uIndices;
uniform sampler2D uPalette;
uniform sampler2D uPrevious;
uniform vec4 uDecay;

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec3 color = toLinear(texelFetch(uPalette, ivec2(int(texelFetch(uIndices, p, 0).r), 0), 0).rgb);
    vec3 glow = uDecay.w > 0.5 ? texelFetch(uPrevious, p, 0).rgb * uDecay.rgb : vec3(0.0);
    fragColor = vec4(max(color, glow), 1.0);
}
"""

    /** Half-size, with a 5-tap kernel: the center and four diagonals. */
    const val DOWN = HEADER + """
uniform sampler2D uSource;
uniform vec2 uOutSize;

void main() {
    vec2 uv = gl_FragCoord.xy / uOutSize;
    vec2 texel = 1.0 / vec2(textureSize(uSource, 0));
    vec3 c = texture(uSource, uv).rgb * 4.0;
    c += texture(uSource, uv + texel * vec2(-1.0, -1.0)).rgb;
    c += texture(uSource, uv + texel * vec2(1.0, -1.0)).rgb;
    c += texture(uSource, uv + texel * vec2(-1.0, 1.0)).rgb;
    c += texture(uSource, uv + texel * vec2(1.0, 1.0)).rgb;
    fragColor = vec4(c / 8.0, 1.0);
}
"""

    /** Up one level with an 8-tap tent, added onto what is there. */
    const val UP = HEADER + """
uniform sampler2D uSource;
uniform vec2 uOutSize;

void main() {
    vec2 uv = gl_FragCoord.xy / uOutSize;
    vec2 texel = 1.0 / vec2(textureSize(uSource, 0));
    vec3 c = vec3(0.0);
    c += texture(uSource, uv + texel * vec2(-1.0, 0.0)).rgb * 2.0;
    c += texture(uSource, uv + texel * vec2(1.0, 0.0)).rgb * 2.0;
    c += texture(uSource, uv + texel * vec2(0.0, -1.0)).rgb * 2.0;
    c += texture(uSource, uv + texel * vec2(0.0, 1.0)).rgb * 2.0;
    c += texture(uSource, uv + texel * vec2(-1.0, -1.0)).rgb;
    c += texture(uSource, uv + texel * vec2(1.0, -1.0)).rgb;
    c += texture(uSource, uv + texel * vec2(-1.0, 1.0)).rgb;
    c += texture(uSource, uv + texel * vec2(1.0, 1.0)).rgb;
    fragColor = vec4(c / 12.0, 1.0);
}
"""

    /**
     * Gaussian beams over neighbouring scanlines whose width grows with
     * brightness, a shadow mask, the glow, a vignette and glass reflection,
     * then sRGB encoding.
     */
    const val PRESENT = HEADER + """
in vec2 vUV;
uniform sampler2D uLight;
uniform sampler2D uBloom;
uniform vec2 uSize;
uniform float uScale;
uniform float uScanlines;
uniform float uBeamDim;
uniform float uBeamBright;
uniform float uMaskStrength;
uniform int uMaskType;
uniform float uHalation;
uniform float uVignette;
uniform float uBrightness;
uniform float uAmbient;
uniform float uBloomLevels;

// One virtual row, sharp horizontally.
vec3 rowLight(float x, int row) {
    int y = clamp(row, 0, int(uSize.y) - 1);
    float v = x - 0.5;
    float base = floor(v);
    float t = clamp((v - base - 0.5) * uScale + 0.5, 0.0, 1.0);
    int x0 = clamp(int(base), 0, int(uSize.x) - 1);
    int x1 = clamp(int(base) + 1, 0, int(uSize.x) - 1);
    return mix(texelFetch(uLight, ivec2(x0, y), 0).rgb, texelFetch(uLight, ivec2(x1, y), 0).rgb, t);
}

void main() {
    vec2 v = vUV * uSize;
    int row = int(floor(v.y));
    vec3 flatLight = rowLight(v.x, row);

    // Gaussian beams from the rows around this one; bright rows swell.
    vec3 beam = vec3(0.0);
    for (int k = -2; k <= 2; k++) {
        int r = row + k;
        vec3 c = rowLight(v.x, r);
        float lum = dot(c, vec3(0.2126, 0.7152, 0.0722));
        float sigma = mix(uBeamDim, uBeamBright, clamp(lum, 0.0, 1.0));
        sigma = sqrt(sigma * sigma + 1.0 / (12.0 * uScale * uScale));
        float d = v.y - (float(r) + 0.5);
        float w = exp(-d * d / (2.0 * sigma * sigma)) / (sigma * 2.5066283);
        beam += c * w;
    }
    // Too few device pixels per row to show lines: fade them out.
    float lines = uScanlines * clamp((uScale - 1.5) / 1.0, 0.0, 1.0);
    vec3 color = mix(flatLight, beam, lines);

    // Shadow mask, compensated so it dims nothing on average.
    if (uMaskType != 0 && uMaskStrength > 0.0) {
        uvec2 d = uvec2(gl_FragCoord.xy);
        uint channel = d.x % 3u;
        float low = 1.0 - uMaskStrength;
        vec3 mask = vec3(low);
        if (uMaskType == 3) channel = (d.x + (d.y % 2u) * 2u) % 3u;
        mask[int(channel)] = 1.0;
        if (uMaskType == 2 && ((d.y + ((d.x / 3u) % 2u) * 2u) % 4u) == 0u) mask *= low;
        color *= mask * (3.0 / (1.0 + 2.0 * low));
    }

    // Halation: the blurred light, without lines or mask.
    vec3 halo = texture(uBloom, vUV).rgb / uBloomLevels;
    color += halo * uHalation;

    vec2 q = vUV * 2.0 - 1.0;
    color *= 1.0 - uVignette * 0.5 * dot(q, q);
    color = color * uBrightness + uAmbient * vec3(0.35, 0.5, 0.7);
    fragColor = vec4(toSrgb(color), 1.0);
}
"""
}
