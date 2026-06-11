#version 460

// Metal LOD depth export (Iris gbuffer injection). Copies the LOD render
// pass's depth attachment into an R32F color target texel-for-texel so the
// IOSurface bridge can hand raw depth to the GL-side compositor — GL cannot
// sample a Metal depth texture, but an R32F color image crosses the
// IOSurface boundary as a plain float texture. texelFetch at gl_FragCoord
// keeps the pass resolution-exact: no filtering, no UV math, and no flip
// (source depth and target share Metal's top-left origin, so output texel
// (x,y) is exactly depth texel (x,y)). Declared as a plain sampler2D — the
// same declaration quads.frag uses for its depth-bound texelFetch, which is
// the transpile pattern verified to read Metal depth textures on-device.
layout(binding = 0) uniform sampler2D depthTex;

layout(location = 0) out vec4 outDepth;

void main() {
    float d = texelFetch(depthTex, ivec2(gl_FragCoord.xy), 0).r;
    // 24-bit RGB pack (classic EncodeFloatRGB): Apple GL samples zeros from
    // R32F IOSurfaces, so depth crosses the bridge as packed bytes in the
    // proven BGRA8 format. Decode GL-side: dot(rgb, vec3(1, 1/255, 1/65025)).
    d = clamp(d, 0.0, 1.0 - 1.0e-7);
    vec3 enc = fract(vec3(1.0, 255.0, 65025.0) * d);
    enc -= enc.yzz * vec3(1.0 / 255.0, 1.0 / 255.0, 0.0);
    outDepth = vec4(enc, 1.0);
}
