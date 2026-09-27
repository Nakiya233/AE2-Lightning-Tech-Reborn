#version 150

in vec3 Position;
in vec4 Color;
in vec2 UV0;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out float surfaceTicks;
out vec4 surfaceShade;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    // UV0 carries the model-local tick offset, independent of camera/world transforms.
    surfaceTicks = UV0.x;
    surfaceShade = Color;
}
