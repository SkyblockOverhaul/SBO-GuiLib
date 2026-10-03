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
flat out vec4 sideWidths; // top, right, bottom, left; only with side >= 0
flat out int side;        // -1, or the side this quad paints of a border with different sides (see SideBorders)

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexColor = Color;
    localPos = UV0;
    halfSize = vec2(UV1) / 8.0;
    int a = UV2.x & 0xFFFF;
    int b = UV2.y & 0xFFFF;
    radii = vec4(float(a & 0xFF), float((a >> 8) & 0xFF), float(b & 0xFF), float((b >> 8) & 0xFF)) / 2.0;
    borderWidth = LineWidth;
    side = -1;
    sideWidths = vec4(0.0);
    if (LineWidth >= 4194304.0) {
        int v = int(LineWidth - 4194304.0 + 0.5);
        side = v & 3;
        sideWidths = vec4(float((v >> 2) & 31), float((v >> 7) & 31), float((v >> 12) & 31), float((v >> 17) & 31)) / 2.0;
        borderWidth = 0.0;
    }
}
