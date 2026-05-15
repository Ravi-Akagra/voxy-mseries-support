#version 460 core
// M9 Phase 2 patch: bumped from 430 to 460 so gl_BaseInstance is a core
// built-in. shaderc/glslang's Vulkan profile rejects
// GL_ARB_shader_draw_parameters as an extension at earlier versions.
#extension GL_ARB_gpu_shader_int64 : enable

#define QUAD_BUFFER_BINDING 1
#define MODEL_BUFFER_BINDING 3
#define MODEL_COLOUR_BUFFER_BINDING 4
#define POSITION_SCRATCH_BINDING 5
#define LIGHTING_SAMPLER_BINDING 1

#ifdef USE_SINGLE_TRI
#define USE_NV_BARRY
#endif

#import <voxy:lod/quad_format.glsl>
#import <voxy:lod/block_model.glsl>
#import <voxy:lod/gl46/bindings.glsl>
#import <voxy:lod/quad_util.glsl>

layout(location = 0) out flat uvec4 interData;
#ifndef USE_NV_BARRY
layout(location = 1) out vec2 uv;
#endif

#ifdef DEBUG_RENDER
layout(location = 7) out flat uint quadDebug;
#endif

// M13 chunk 5: Per-vertex world-space distance from the camera, interpolated
// linearly across the quad. Used by quads.frag's USE_ENV_FOG path to mix in
// the environmental fog colour at far LOD distances. The vertex's basePoint
// + corner offset already share the same section-relative origin as
// cameraSubPos (see setupQuad in quad_util.glsl — both are post
// `- baseSectionPos<<5`), so `length(cornerPoint - cameraSubPos)` is the
// real world-space distance without needing to round-trip through the MVP.
#ifdef USE_ENV_FOG
layout(location = 2) out float voxyFogDist;
#endif

vec2 taaShift();

//TODO: add a mechanism so that some quads can ignore backface culling
// this would help alot with stuff like crops as they would look kinda weird i think,
// same with flowers etc
void main() {
    taaOffset = taaShift();

    QuadData quad;
    setupQuad(quad, quadData[uint(gl_VertexID)>>2], positionBuffer[gl_BaseInstance], (gl_VertexID&3) == 1);

    uint cornerId = gl_VertexID&3;
    gl_Position = getQuadCornerPos(quad, cornerId);

    #ifndef USE_NV_BARRY
    uv = getCornerUV(quad, cornerId);
    #endif

    //Note: other data is automatically discarded as it is undefiend and has not been generated
    interData = quad.attributeData;

    #ifdef USE_ENV_FOG
    // Reconstruct the corner's world-relative point in the same way
    // getQuadCornerPos does (kept inline rather than refactoring quad_util
    // to avoid touching the GL path's hot vertex code). cameraSubPos comes
    // from the SceneUniform SSBO declared above; both points share the
    // baseSectionPos-anchored frame.
    vec2 cornerMask = vec2((cornerId>>1)&1u, cornerId&1u)*quad.lodScale;
    vec3 cornerPoint = quad.basePoint + swizzelDataAxis(quad.axis, vec3(quad.quadSizeAddin*cornerMask, 0));
    voxyFogDist = length(cornerPoint - cameraSubPos);
    #endif

    #ifdef DEBUG_RENDER
    quadDebug = uint(gl_VertexID)>>(2+5);
    #endif
}

#ifndef TAA_PATCH
vec2 taaShift() {return vec2(0.0);}
#endif