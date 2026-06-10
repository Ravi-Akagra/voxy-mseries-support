/*
struct SectionMeta {
    uint posA;
    uint posB;
    uint AABB;
    uint ptr;
    uint cntA;
    uint cntB;
    uint cntC;
    uint cntD;
};
*/
struct SectionMeta {
    uvec4 a;
    uvec4 b;
};

uvec2 extractRawPos(SectionMeta section) {
    return section.a.xy;
}

uint extractDetail(SectionMeta section) {
    return section.a.x>>28;
}

ivec3 extractPosition(SectionMeta section) {
    //Metal fix: (v<<L)>>R sign-extension shifts miscompile via SPIR-V->MSL (see screenspace.glsl); bitfieldExtract is well-defined
    int y = bitfieldExtract(int(section.a.x), 20, 8);
    int x = bitfieldExtract(int(section.a.y), 4, 24);
    int z = bitfieldExtract(int(((section.a.x&((1u<<20)-1))<<4)|(section.a.y>>28)), 0, 24);
    return ivec3(x,y,z);
}

uint extractQuadStart(SectionMeta meta) {
    return meta.a.w;
}

ivec3 extractAABBOffset(SectionMeta meta) {
    return (ivec3(meta.a.z)>>ivec3(0,5,10))&31;
}

ivec3 extractAABBSize(SectionMeta meta) {
    return ((ivec3(meta.a.z)>>ivec3(15,20,25))&31)+1;//The size is + 1 cause its always at least 1x1x1
}
