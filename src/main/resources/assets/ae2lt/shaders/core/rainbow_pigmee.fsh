#version 150

uniform float AnimationTicks;

in float surfaceTicks;
in vec4 surfaceShade;

out vec4 fragColor;

void main() {
    // Every surface point uses its own clock: position offset + real game tick + partial tick.
    float phase = fract((surfaceTicks + AnimationTicks) / 160.0);
    vec3 hue = clamp(abs(fract(phase + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0) - 1.0, 0.0, 1.0);
    hue = hue * hue * (3.0 - 2.0 * hue);
    vec3 rainbow = mix(vec3(1.0), hue, 0.64);
    float highlight = pow(0.5 + 0.5 * cos(phase * 6.2831853), 10.0) * 0.12;
    fragColor = vec4(mix(rainbow, vec3(1.0), highlight) * surfaceShade.rgb, surfaceShade.a);
}
