#version 330

// Same uniform blocks as vanilla core/gui.vsh.
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};
layout(std140) uniform Projection {
    mat4 ProjMat;
};

in vec3 Position;
in vec4 Color;
in vec2 UV0;      // position relative to the rect center (GUI px)
in ivec2 UV1;     // half size * 8
in ivec2 UV2;     // radii * 2 packed: x = tl | tr << 8, y = br | bl << 8
in float LineWidth;

out vec4 vertexColor;
out vec2 localPos;
flat out vec2 halfSize;
flat out vec4 radii;
flat out float borderWidth;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexColor = Color;
    localPos = UV0;
    halfSize = vec2(UV1) / 8.0;
    int a = UV2.x & 0xFFFF;
    int b = UV2.y & 0xFFFF;
    radii = vec4(float(a & 0xFF), float((a >> 8) & 0xFF), float(b & 0xFF), float((b >> 8) & 0xFF)) / 2.0;
    borderWidth = LineWidth;
}
