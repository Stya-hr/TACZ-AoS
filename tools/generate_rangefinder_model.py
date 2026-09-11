from pathlib import Path
import json, math, random, struct, zlib
root=Path('src/main/resources'); assets=root/'assets/tacz_aos'; data=root/'data/tacz_aos'
def write(path,obj):
 path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(obj,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
# Hand-authored 64x64 pixel atlas for the native JSON cuboid model.
pixels=[[(24,28,29,255) for x in range(64)] for y in range(64)]
def tile(x,y,w,h,color):
 for j in range(y,y+h):
  for i in range(x,x+w):
   noise=((i*17+j*29)%7)-3
   pixels[j][i]=tuple(max(0,min(255,c+noise)) for c in color)+(255,)
def rect(x,y,w,h,color):
 for j in range(y,y+h):
  for i in range(x,x+w): pixels[j][i]=(*color,255)
tile(0,0,32,32,(37,42,43));tile(32,0,16,16,(52,59,60));tile(48,0,16,16,(18,22,23))
tile(32,16,16,16,(9,37,46));tile(48,16,16,16,(7,20,17));tile(0,32,16,16,(98,107,105))
tile(16,32,16,16,(31,37,38));tile(32,32,16,16,(23,28,29));tile(48,32,16,16,(38,43,44))
for i in (1,30): rect(i,0,1,32,(52,58,58));rect(0,i,32,1,(52,58,58))
for x in (3,28):
 for y in (3,28):rect(x,y,1,1,(91,99,97))
for x in (34,38,42,46): rect(x,1,1,14,(68,74,73))
rect(33,17,14,1,(83,132,143));rect(33,18,1,10,(46,101,116));rect(36,20,5,1,(37,78,90));rect(44,27,2,2,(11,21,27))
rect(49,17,14,1,(40,57,47));rect(50,20,9,1,(66,148,93));rect(50,23,5,1,(57,114,73));rect(58,23,3,1,(57,114,73));rect(50,27,11,1,(24,60,38))
for x in (34,39,44):rect(x,36,3,4,(85,96,94));rect(x,40,3,1,(9,15,17))
rect(34,44,2,2,(68,131,81))
for x in (50,55,60):rect(x,33,1,14,(63,69,69))
# Small neutral identification plate; no reference photograph branding.
letters={'L':['100','100','100','100','111'],'R':['110','101','110','101','101']}
for k,ch in enumerate('LR'):
 for y,row in enumerate(letters[ch]):
  for x,v in enumerate(row):
   if v=='1':rect(19+k*5+x,36+y,1,1,(181,190,180))
def png(path):
 raw=b''.join(b'\x00'+bytes(c for px in row for c in px) for row in pixels)
 def chunk(t,b):return struct.pack('>I',len(b))+t+b+struct.pack('>I',zlib.crc32(t+b)&0xffffffff)
 path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',64,64,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(raw))+chunk(b'IEND',b''))
png(assets/'textures/block/rangefinder.png')
uv={'body':[0,0,8,8],'rail':[8,0,12,4],'rubber':[12,0,16,4],'glass':[8,4,12,8],'screen':[12,4,16,8], 'steel':[0,8,4,12],'label':[4,8,8,12],'controls':[8,8,12,12],'top':[12,8,16,12]}
def cube(name,a,b,material='body',rotation=None,faces=None):
 e={'name':name,'from':a,'to':b,'faces':{side:{'uv':uv[(faces or {}).get(side,material)],'texture':'#atlas'} for side in ('north','south','east','west','up','down')}}
 if rotation:e['rotation']=rotation
 return e
legs=[]
for name,axis,angle in [('left_leg','z',-22.5),('right_leg','z',22.5),('rear_leg','x',-22.5)]:
 legs.append(cube(name,[7.25,-1.2,6.75],[8.75,18.25,8.25],'rail',{'origin':[8,18.25,7.5],'axis':axis,'angle':angle,'rescale':False}))
head=[cube('tripod_hub',[5.5,18,5],[10.5,20.5,10],'rubber'),cube('mount_column',[7,20.5,7],[9,22,9],'steel'),
 cube('mount_plate',[4.5,21.5,5],[11.5,22.25,11],'rail'),cube('instrument_body',[2.5,22,4],[13.5,29,13],'body',faces={'east':'label','up':'top'}),
 cube('top_rail_left',[3,29,4.25],[3.6,29.6,12.75],'rail'),cube('top_rail_right',[12.4,29,4.25],[13,29.6,12.75],'rail'),
 cube('objective_mount',[3.5,23.5,3],[8.5,28.5,4.1],'rail'),cube('objective_barrel',[4,24,1],[8,28,3],'rubber'),
 cube('objective_glass',[4.4,24.4,.9],[7.6,27.6,1],'glass'),cube('secondary_sensor',[10,25,3.1],[12,27,4],'rubber'),
 cube('secondary_glass',[10.3,25.3,3],[11.7,26.7,3.1],'glass'),cube('display_bezel',[7.5,24,13],[12.75,28,13.65],'rubber'),
 cube('display',[8,24.5,13.65],[12.25,27.5,13.75],'screen'),cube('keypad',[8,22.7,13],[12.5,24,13.6],'controls'),
 cube('eyepiece',[3.5,24.75,13],[6.5,27.75,14.5],'rubber'),cube('eyepiece_glass',[4,25.25,14.5],[6,27.25,14.6],'glass'),
 cube('side_adjustment',[13.5,22.5,7],[14.5,24.5,9],'rail')]
texture={'atlas':'tacz_aos:block/rangefinder','particle':'minecraft:block/anvil'}
# Legs cross the seam slightly; all instrument parts are in the upper model, so either chunk section renders correctly.
upper=[]
for original in head:
 e=json.loads(json.dumps(original));e['from'][1]-=16;e['to'][1]-=16;upper.append(e)
for name,elements in [('rangefinder_lower',legs),('rangefinder_upper',upper)]:write(assets/f'models/block/{name}.json',{'textures':texture,'elements':elements})
display={'gui':{'rotation':[25,225,0],'translation':[0,-2,0],'scale':[.48,.48,.48]},'ground':{'rotation':[0,0,0],'translation':[0,3,0],'scale':[.25,.25,.25]},'fixed':{'rotation':[0,180,0],'translation':[0,-4,0],'scale':[.5,.5,.5]},'thirdperson_righthand':{'rotation':[75,45,0],'translation':[0,2.5,0],'scale':[.25,.25,.25]},'firstperson_righthand':{'rotation':[0,45,0],'translation':[0,-1,0],'scale':[.35,.35,.35]}}
write(assets/'models/item/rangefinder.json',{'textures':texture,'display':display,'elements':legs+head})
variants={}
for half in ['lower','upper']:
 for facing,angle in [('north',0),('east',90),('south',180),('west',270)]:variants[f'facing={facing},half={half}']={'model':f'tacz_aos:block/rangefinder_{half}','y':angle}
write(assets/'blockstates/rangefinder.json',{'variants':variants})
write(data/'recipe/rangefinder.json',{'type':'minecraft:crafting_shaped','category':'misc','pattern':['ABA',' C ','C C'],'key':{'A':{'item':'minecraft:glass_pane'},'B':{'item':'minecraft:diamond'},'C':{'item':'minecraft:iron_ingot'}},'result':{'id':'tacz_aos:rangefinder','count':1}})
write(data/'loot_table/blocks/rangefinder.json',{'type':'minecraft:block','pools':[{'rolls':1,'conditions':[{'condition':'minecraft:survives_explosion'},{'condition':'minecraft:block_state_property','block':'tacz_aos:rangefinder','properties':{'half':'lower'}}],'entries':[{'type':'minecraft:item','name':'tacz_aos:rangefinder'}]}]})
write(root/'data/minecraft/tags/block/mineable/axe.json',{'replace':False,'values':['tacz_aos:rangefinder']})
write(data/'advancement/recipes/decorations/rangefinder.json',{'parent':'minecraft:recipes/root','criteria':{'has_diamond':{'trigger':'minecraft:inventory_changed','conditions':{'items':[{'items':'minecraft:diamond'}]}},'has_the_recipe':{'trigger':'minecraft:recipe_unlocked','conditions':{'recipe':'tacz_aos:rangefinder'}}},'requirements':[['has_diamond','has_the_recipe']],'rewards':{'recipes':['tacz_aos:rangefinder']}})
for language,name in [('en_us','Rangefinder'),('zh_cn','测距仪')]:
 p=assets/f'lang/{language}.json';o=json.loads(p.read_text(encoding='utf-8'));o['block.tacz_aos.rangefinder']=name;write(p,o)
