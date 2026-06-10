struct Frustum {
    vec4 planes[6];
};

bool testPlane(vec4 plane, vec3 base, float size) {
    // Pick the AABB corner that maximises dot(plane.xyz, corner) (the "p-vertex"):
    // add `size` on each axis where the plane normal is >= 0, else add 0.
    // Metal fix (2026-05-27): the original used mix(vec3,vec3,bvec3) — the
    // boolean-"select" overload of mix. That form transpiles unreliably to MSL
    // (SPIR-V Cross), so on Metal the wrong corner is chosen and AABBs that
    // straddle the side planes (i.e. sections at the LEFT/RIGHT screen edges)
    // are culled wrongly — the terrain that disappears at the edges. step() is
    // a plain float builtin that transpiles correctly and is equivalent:
    // step(0, n) == 1 where n >= 0, else 0.
    vec3 pVertex = base + size * step(vec3(0.0), plane.xyz);
    return dot(plane.xyz, pVertex) >= -plane.w;
}

//TODO: optimize this, this can be done by computing the base point value, then multiplying and adding a seperate value by the size
bool outsideFrustum(in Frustum frustum, vec3 pos, float size) {
    return !(testPlane(frustum.planes[0], pos, size) && testPlane(frustum.planes[1], pos, size) &&
           testPlane(frustum.planes[2], pos, size) && testPlane(frustum.planes[3], pos, size) &&
           testPlane(frustum.planes[4], pos, size));//Dont need to test far plane
}