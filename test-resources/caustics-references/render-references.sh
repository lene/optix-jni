#!/bin/bash
# render-references.sh
# Renders caustics reference images using available renderers
#
# Usage: ./render-references.sh [renderer]
#   renderer: pbrt, mitsuba, appleseed, or all (default: all)
#
# Prerequisites:
#   - PBRT v4: https://github.com/mmp/pbrt-v4
#   - Mitsuba 3: https://mitsuba.readthedocs.io/
#   - Appleseed: https://appleseedhq.net/
#
# Output: renders/*.png files

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RENDERS_DIR="$SCRIPT_DIR/renders"
OUTPUT_DIR="$SCRIPT_DIR/output"

mkdir -p "$OUTPUT_DIR"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check for renderer availability
check_pbrt() {
    if command -v pbrt &> /dev/null; then
        log_info "PBRT v4 found: $(pbrt --version 2>&1 | head -1)"
        return 0
    else
        log_warn "PBRT v4 not found. Install from https://github.com/mmp/pbrt-v4"
        return 1
    fi
}

check_mitsuba() {
    if python3 -c "import mitsuba" &> /dev/null; then
        log_info "Mitsuba 3 found"
        return 0
    else
        log_warn "Mitsuba 3 not found. Install with: pip install mitsuba"
        return 1
    fi
}

check_appleseed() {
    if command -v appleseed.cli &> /dev/null; then
        log_info "Appleseed found: $(appleseed.cli --version 2>&1 | head -1)"
        return 0
    else
        log_warn "Appleseed not found. Install from https://appleseedhq.net/"
        return 1
    fi
}

check_imgtool() {
    if command -v imgtool &> /dev/null; then
        return 0
    else
        log_warn "imgtool not found (part of PBRT). EXR conversion may fail."
        return 1
    fi
}

# Render with PBRT
render_pbrt() {
    log_info "Rendering canonical scene with PBRT v4..."

    cd "$RENDERS_DIR"

    # Render the canonical scene
    if pbrt canonical-caustics.pbrt; then
        log_info "PBRT render complete: canonical-caustics.exr"

        # Convert to PNG if imgtool is available
        if check_imgtool; then
            imgtool convert canonical-caustics.exr "$OUTPUT_DIR/canonical-caustics-pbrt.png"
            log_info "Converted to PNG: $OUTPUT_DIR/canonical-caustics-pbrt.png"
        fi
    else
        log_error "PBRT render failed"
        return 1
    fi
}

# Render with Mitsuba (requires XML scene file)
render_mitsuba() {
    log_info "Generating Mitsuba scene and rendering..."

    # Create Mitsuba XML scene
    cat > "$RENDERS_DIR/canonical-caustics.xml" << 'MITSUBA_XML'
<?xml version="1.0" encoding="utf-8"?>
<scene version="3.0.0">
    <!-- Integrator: Stochastic Progressive Photon Mapping -->
    <integrator type="ptracer">
        <integer name="max_depth" value="32"/>
    </integrator>

    <!-- Camera at (0, 1, 4) looking at origin -->
    <sensor type="perspective">
        <string name="fov_axis" value="smaller"/>
        <float name="fov" value="45"/>
        <transform name="to_world">
            <lookat origin="0, 1, 4" target="0, 0, 0" up="0, 1, 0"/>
        </transform>
        <sampler type="independent">
            <integer name="sample_count" value="256"/>
        </sampler>
        <film type="hdrfilm">
            <integer name="width" value="800"/>
            <integer name="height" value="600"/>
            <string name="file_format" value="openexr"/>
            <string name="pixel_format" value="rgb"/>
        </film>
    </sensor>

    <!-- Point light from above -->
    <emitter type="point">
        <point name="position" x="0" y="10" z="0"/>
        <rgb name="intensity" value="500, 500, 500"/>
    </emitter>

    <!-- Glass sphere at origin, IOR 1.5 -->
    <shape type="sphere">
        <point name="center" x="0" y="0" z="0"/>
        <float name="radius" value="1.0"/>
        <bsdf type="dielectric">
            <float name="int_ior" value="1.5"/>
            <float name="ext_ior" value="1.0"/>
        </bsdf>
    </shape>

    <!-- Floor plane at Y = -2 -->
    <shape type="rectangle">
        <transform name="to_world">
            <rotate x="1" angle="-90"/>
            <scale x="10" y="10" z="1"/>
            <translate y="-2"/>
        </transform>
        <bsdf type="diffuse">
            <rgb name="reflectance" value="0.8, 0.8, 0.8"/>
        </bsdf>
    </shape>
</scene>
MITSUBA_XML

    log_info "Created Mitsuba scene: $RENDERS_DIR/canonical-caustics.xml"

    # Render with Mitsuba
    python3 << MITSUBA_RENDER
import mitsuba as mi
mi.set_variant('scalar_rgb')

scene = mi.load_file('$RENDERS_DIR/canonical-caustics.xml')
image = mi.render(scene)
mi.util.write_bitmap('$OUTPUT_DIR/canonical-caustics-mitsuba.exr', image)
print('Mitsuba render complete')
MITSUBA_RENDER

    if [ -f "$OUTPUT_DIR/canonical-caustics-mitsuba.exr" ]; then
        log_info "Mitsuba render complete: $OUTPUT_DIR/canonical-caustics-mitsuba.exr"
    else
        log_error "Mitsuba render failed"
        return 1
    fi
}

# Render with Appleseed
render_appleseed() {
    log_info "Rendering Cornell box with Appleseed..."

    APPLESEED_SCENE="$SCRIPT_DIR/appleseed/cornell-box-caustics.appleseed"

    if [ ! -f "$APPLESEED_SCENE" ]; then
        log_error "Appleseed scene not found: $APPLESEED_SCENE"
        return 1
    fi

    # Appleseed requires the .obj files in the same directory
    # The scene references external meshes that we don't have
    log_warn "Appleseed Cornell box requires .obj mesh files not included in this download"
    log_warn "Skipping Appleseed render. To render manually:"
    log_warn "  1. Download full Appleseed test scenes from GitHub"
    log_warn "  2. Run: appleseed.cli cornell-box-caustics.appleseed -o output.png"

    return 1
}

# Generate comparison report
generate_report() {
    log_info "Generating comparison report..."

    REPORT="$OUTPUT_DIR/render-report.md"

    cat > "$REPORT" << EOF
# Caustics Reference Render Report

Generated: $(date)

## Rendered Images

EOF

    for img in "$OUTPUT_DIR"/*.png "$OUTPUT_DIR"/*.exr; do
        if [ -f "$img" ]; then
            echo "- $(basename "$img")" >> "$REPORT"
        fi
    done

    cat >> "$REPORT" << EOF

## Scene Parameters

All renders use the canonical test scene:

| Parameter | Value |
|-----------|-------|
| Sphere center | (0, 0, 0) |
| Sphere radius | 1.0 |
| Sphere IOR | 1.5 |
| Floor Y | -2.0 |
| Light position | (0, 10, 0) |
| Camera position | (0, 1, 4) |
| Resolution | 800 × 600 |

## Usage

Copy the preferred reference image to test resources:

\`\`\`bash
cp output/canonical-caustics-pbrt.png ../../src/test/resources/caustics-reference.png
\`\`\`
EOF

    log_info "Report generated: $REPORT"
}

# Main
main() {
    local renderer="${1:-all}"

    echo "=========================================="
    echo "Caustics Reference Image Renderer"
    echo "=========================================="
    echo ""

    case "$renderer" in
        pbrt)
            if check_pbrt; then
                render_pbrt
            fi
            ;;
        mitsuba)
            if check_mitsuba; then
                render_mitsuba
            fi
            ;;
        appleseed)
            if check_appleseed; then
                render_appleseed
            fi
            ;;
        all)
            log_info "Attempting to render with all available renderers..."
            echo ""

            if check_pbrt; then
                render_pbrt || true
            fi
            echo ""

            if check_mitsuba; then
                render_mitsuba || true
            fi
            echo ""

            if check_appleseed; then
                render_appleseed || true
            fi
            ;;
        *)
            echo "Usage: $0 [pbrt|mitsuba|appleseed|all]"
            exit 1
            ;;
    esac

    echo ""
    generate_report

    echo ""
    echo "=========================================="
    echo "Done! Check $OUTPUT_DIR for rendered images"
    echo "=========================================="
}

main "$@"
