"""Recover the hidden model's bind pose from the pinned resource pack.

Extra face/finger joints are retargeted to the adult's 21 animated joints.
Geometry and original costume textures remain intact; no SPK is modified.
"""
import pathlib, struct, sys

ROOT=pathlib.Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT.parent/'reverse-engineering'))
from spk_tool import read_index

def main():
    pack=ROOT/'assets/res.spk'
    entry=next(e for e in read_index(pack) if e['name'].endswith('r_kenh_a.sai'))
    with pack.open('rb') as stream:
        stream.seek(entry['offset']);data=stream.read(entry['size'])
    count,=struct.unpack_from('<I',data,36)
    if count!=67:raise ValueError('Unexpected hidden model skeleton')
    pose=[];offset=40
    for bone in range(count):
        frames,=struct.unpack_from('<I',data,offset);offset+=4
        if frames!=1:raise ValueError('Expected a single bind frame')
        sign,=struct.unpack_from('<H',data,offset+4)
        rotation=[v/65536*(-1 if sign>>i&1 else 1) for i,v in enumerate(struct.unpack_from('<9H',data,offset+6))]
        position=[v/65536 for v in struct.unpack_from('<3i',data,offset+24)]
        pose.append((rotation,position));offset+=36
    if offset!=len(data):raise ValueError('Unexpected bind pose tail')
    out='// Generated from original r_kenh_a.sai by prepare_costumes.py.\n'
    out+='struct CostumeJoint {int parent;float rotation[9],position[3];};\nstatic const CostumeJoint costume_joints[]={\n'
    for bone,(rotation,position) in enumerate(pose):
        parent=bone if bone<21 else min(range(21),key=lambda j:sum((position[k]-pose[j][1][k])**2 for k in range(3)))
        fmt=lambda values:','.join(f'{v:.9f}f' for v in values)
        out+='{'+str(parent)+',{'+fmt(rotation)+'},{'+fmt(position)+'}},\n'
    out+='};\n'
    (ROOT/'native/costume_pose.h').write_text(out,encoding='utf-8')
    entries=read_index(pack)
    def resource(name):
        row=next(e for e in entries if e['name'].endswith(name))
        with pack.open('rb') as stream:
            stream.seek(row['offset']);return stream.read(row['size'])
    def frames(data):
        result=[];offset=40
        for bone in range(struct.unpack_from('<I',data,36)[0]):
            n,=struct.unpack_from('<I',data,offset);offset+=4
            signs,=struct.unpack_from('<H',data,offset+4)
            rotation=[v/65536*(-1 if signs>>j&1 else 1) for j,v in enumerate(struct.unpack_from('<9H',data,offset+6))]
            position=[v/65536 for v in struct.unpack_from('<3i',data,offset+24)]
            result.append((rotation,position));offset+=n*36
        return result
    def mesh(data):
        n,=struct.unpack_from('<I',data,88)
        return [(struct.unpack_from('<I',data,96+i*36)[0]&65535,[v/65536 for v in struct.unpack_from('<3i',data,100+i*36)]) for i in range(n)]
    def world(vertex,poses):
        bone,p=vertex;r,t=poses[bone]
        return [sum(p[j]*r[j*3+k] for j in range(3))+t[k] for k in range(3)]
    adult_pose=frames(resource('r_ken_stand_a.sai'))
    adult_mesh=mesh(resource('r_ken_a.soj'))
    adult_world=[world(vertex,adult_pose) for vertex in adult_mesh]
    hidden_mesh=mesh(resource('r_kenh_a.soj'))
    converted=bytearray(struct.pack('<II',0x314d5343,len(hidden_mesh)))
    for vertex in hidden_mesh:
        point=world(vertex,pose)
        near=min(range(len(adult_world)),key=lambda j:sum((point[k]-adult_world[j][k])**2 for k in range(3)))
        bone=adult_mesh[near][0];r,t=adult_pose[bone]
        delta=[point[k]-t[k] for k in range(3)]
        local=[sum(delta[k]*r[j*3+k] for k in range(3)) for j in range(3)]
        converted+=struct.pack('<I3f',bone,*local)
    (ROOT/'assets/costume-vertices.bin').write_bytes(converted)
    print('Recovered hidden costume bind pose')

if __name__=='__main__':main()
