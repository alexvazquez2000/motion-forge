import FreeCAD
import Part
import Mesh
import Import
import os

# Run with:     & "C:\Program Files\FreeCAD 1.1\bin\freecadcmd.exe" convert_ur10_step_stl.py
#
# Exports the UR10g-1750 links from the ASSEMBLY STEP so every link shares one world frame.
#
# The UR STEP is Z-up (arm modeled pointing straight up, all joints at their visual zero).
# The Meca500 STLs use "up = -Y", so every shape is rotated +90 deg about X before export:
#     (x, y, z)  ->  (x, -z, y)
# This keeps both robots in the same convention and needs no extra transform in JavaFX.
#
# Joint axes found from the coaxial cylindrical faces between adjacent parts (STEP frame, mm):
#     J1  Z axis through (x=0, y=0)           base      -> shoulder
#     J2  Y axis through (x=0, z=218.6)       shoulder  -> upper arm
#     J3  Y axis through (x=0, z=1117.5)      upper arm -> forearm
#     J4  Y axis through (x=0, z=1832.4)      forearm   -> wrist 1
#     J5  Z axis through (x=0, y=-182.44)     wrist 1   -> wrist 2
#     J6  Y axis through (x=0, z=1968.5)      wrist 2   -> wrist 3 (flange face at y=-326.4)
# After the export rotation these become the pivots used in UR10g1750.java.

assembly_path = "C:/Users/alexv/workspace/meca500-sim/src/main/resources/UR10/UR10g-1750.step/UR10g-1750.step/Robot arm UR10g-1750.STEP"
output_dir    = "C:/Users/alexv/workspace/meca500-sim/src/main/resources/UR10/"

# Output file -> STEP part labels merged into it.
# C-2011656 is the base cable connector (fixed to the base).
# C-2011521 is the tool connector ring (coaxial with J6, rides on wrist 3).
links = {
    "Link0_base.stl":   ["C-2011363", "C-2011656"],
    "Link1.stl":        ["C-2011356"],
    "Link2.stl":        ["C-2011357"],
    "Link3.stl":        ["C-2011358"],
    "Link4.stl":        ["C-2011359"],
    "Link5.stl":        ["C-2011361"],
    "Link6_flange.stl": ["C-2011362", "C-2011521"],
}

# Mesh deviation in mm. The UR10 is ~7x larger than the Meca500, so a coarser value keeps files small.
TESSELLATION = 0.2

doc = FreeCAD.newDocument("ur10")
Import.insert(assembly_path, doc.Name)
by_label = {o.Label: o for o in doc.Objects if o.TypeId == "Part::Feature"}
print(f"Found {len(by_label)} parts: {sorted(by_label)}\n")

used = set()
for name, labels in links.items():
    mesh = Mesh.Mesh()
    bbox = None
    for label in labels:
        shape = by_label[label].Shape.copy()
        shape.rotate(FreeCAD.Vector(0, 0, 0), FreeCAD.Vector(1, 0, 0), 90)
        mesh.addMesh(Mesh.Mesh(shape.tessellate(TESSELLATION)))
        bbox = shape.BoundBox if bbox is None else bbox.united(shape.BoundBox)
        used.add(label)
    out_path = os.path.join(output_dir, name)
    mesh.write(out_path)
    print(f"  X[{bbox.XMin:8.2f}, {bbox.XMax:8.2f}]"
          f"  Y[{bbox.YMin:8.2f}, {bbox.YMax:8.2f}]"
          f"  Z[{bbox.ZMin:8.2f}, {bbox.ZMax:8.2f}]"
          f"  {mesh.CountFacets:7d} facets  -> {name}  ({', '.join(labels)})")

unused = set(by_label) - used
if unused:
    print(f"\nWARNING: parts not exported: {sorted(unused)}")

print(f"\nDone. Files written to {output_dir}")
