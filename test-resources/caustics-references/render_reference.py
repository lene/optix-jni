#!/usr/bin/env python3
"""
Render the canonical caustics reference image using Mitsuba 3.

This script creates a scene matching the arc42 Section 10 canonical test scene:
- Glass sphere at origin, radius 1.0, IOR 1.5
- Floor plane at Y = -2.0
- Point light at (0, 10, 0)
- Camera at (0, 1, 4) looking at origin

Output: canonical-caustics-reference.png (800x600)
"""

import mitsuba as mi
import numpy as np
import os

# Use scalar RGB variant for CPU rendering (no GPU required)
mi.set_variant('scalar_rgb')

def create_canonical_scene():
    """Create the canonical caustics test scene."""
    return mi.load_dict({
        'type': 'scene',

        # Integrator: Path tracer with many bounces for caustics
        'integrator': {
            'type': 'path',
            'max_depth': 32,
        },

        # Camera at (0, 1, 4) looking at origin
        'sensor': {
            'type': 'perspective',
            'fov': 45,
            'to_world': mi.ScalarTransform4f().look_at(
                origin=[0, 1, 4],
                target=[0, 0, 0],
                up=[0, 1, 0]
            ),
            'film': {
                'type': 'hdrfilm',
                'width': 800,
                'height': 600,
                'pixel_format': 'rgb',
                'component_format': 'float32',
            },
            'sampler': {
                'type': 'independent',
                'sample_count': 4096,  # Very high sample count for caustics
            },
        },

        # Area light at (0, 10, 0) - directly above sphere (works better for caustics)
        'light': {
            'type': 'rectangle',
            'to_world': mi.ScalarTransform4f()
                .rotate([1, 0, 0], 90)  # Face downward
                .scale([2, 2, 1])       # 2x2 area light
                .translate([0, 10, 0]), # Position above
            'emitter': {
                'type': 'area',
                'radiance': {
                    'type': 'rgb',
                    'value': [5000, 5000, 5000],  # Bright area light
                },
            },
        },

        # Dim environment for ambient fill
        'envmap': {
            'type': 'constant',
            'radiance': {
                'type': 'rgb',
                'value': [0.1, 0.1, 0.1],  # Dim ambient
            },
        },

        # Glass sphere at origin, radius 1.0, IOR 1.5
        'sphere': {
            'type': 'sphere',
            'center': [0, 0, 0],
            'radius': 1.0,
            'bsdf': {
                'type': 'dielectric',
                'int_ior': 1.5,  # Glass
                'ext_ior': 1.0,  # Air
            },
        },

        # Floor plane at Y = -2.0
        'floor': {
            'type': 'rectangle',
            'to_world': mi.ScalarTransform4f()
                .rotate([1, 0, 0], -90)  # Rotate to horizontal
                .scale([20, 20, 1])       # Large floor
                .translate([0, -2, 0]),   # Position at Y = -2
            'bsdf': {
                'type': 'diffuse',
                'reflectance': {
                    'type': 'rgb',
                    'value': [0.8, 0.8, 0.8],  # Light gray
                },
            },
        },
    })


def tonemap_reinhard(image, exposure=1.0):
    """Apply Reinhard tone mapping to HDR image."""
    # Convert to numpy array
    img = np.array(image)

    # Apply exposure
    img = img * exposure

    # Reinhard tone mapping: L / (1 + L)
    img = img / (1.0 + img)

    # Gamma correction
    img = np.power(np.clip(img, 0, 1), 1.0/2.2)

    # Convert to 8-bit
    img = (img * 255).astype(np.uint8)

    return img


def main():
    print("Rendering canonical caustics reference image...")
    print("Scene parameters:")
    print("  Sphere: center=(0,0,0), radius=1.0, IOR=1.5")
    print("  Floor: Y=-2.0, diffuse gray")
    print("  Light: point at (0,10,0)")
    print("  Camera: (0,1,4) looking at origin")
    print("  Resolution: 800x600")
    print("  Samples: 4096")
    print()

    # Create scene
    scene = create_canonical_scene()

    # Render
    print("Rendering... (this may take a few minutes)")
    image = mi.render(scene)

    # Save as EXR (high dynamic range)
    output_dir = os.path.dirname(os.path.abspath(__file__))
    exr_path = os.path.join(output_dir, 'output', 'canonical-caustics-mitsuba.exr')
    png_path = os.path.join(output_dir, 'output', 'canonical-caustics-reference.png')

    os.makedirs(os.path.dirname(exr_path), exist_ok=True)

    mi.util.write_bitmap(exr_path, image)
    print(f"Saved EXR: {exr_path}")

    # Apply tone mapping for PNG
    print("Applying Reinhard tone mapping...")
    tonemapped = tonemap_reinhard(image, exposure=2.0)

    # Save using PIL
    try:
        from PIL import Image
        img_pil = Image.fromarray(tonemapped)
        img_pil.save(png_path)
        print(f"Saved tone-mapped PNG: {png_path}")
    except ImportError:
        # Fallback to Mitsuba's built-in (no tone mapping)
        mi.util.write_bitmap(png_path, image)
        print(f"Saved PNG (no tone mapping - install PIL for better results): {png_path}")

    print()
    print("Done! Reference image saved.")
    print(f"Copy to test resources with:")
    print(f"  cp {png_path} ../../src/test/resources/caustics-reference.png")


if __name__ == '__main__':
    main()
