package ru.dagxam.boatsmove.ship;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Renders compartment-local water volumes and active leak effects. */
public final class ShipFloodVisualManager {
    private static final int MAX_WATER_DISPLAYS=160,MAX_PER_COMPARTMENT=32;
    private final JavaPlugin plugin; private final ShipRegistry registry; private final ShipFloodingManager flooding;
    private final Map<UUID,List<BlockDisplay>> displays=new HashMap<>(); private long tick;
    public ShipFloodVisualManager(JavaPlugin plugin,ShipRegistry registry){this(plugin,registry,null);}
    public ShipFloodVisualManager(JavaPlugin plugin,ShipRegistry registry,ShipFloodingManager flooding){this.plugin=plugin;this.registry=registry;this.flooding=flooding;}
    public void tick(){tick++;for(ShipModel ship:registry.all()){if(ship.state()!=ShipState.ACTIVE||ship.blockCount()==0){clear(ship.id());continue;}update(ship);}}
    private void update(ShipModel ship){if(flooding==null)return;List<ShipFloodingManager.CompartmentWater> cs=flooding.compartmentWater(ship);if(cs.isEmpty()){clear(ship.id());return;}Location base=registry.position(ship);World world=base.getWorld();if(world==null)return;List<BlockDisplay> list=displays.computeIfAbsent(ship.id(),k->new ArrayList<>());int wanted=0;for(var c:cs)wanted+=Math.min(MAX_PER_COMPARTMENT,Math.max(1,(int)Math.ceil(Math.sqrt(c.volume())*c.level())));wanted=Math.min(MAX_WATER_DISPLAYS,wanted);while(list.size()<wanted)list.add(spawn(world,base));while(list.size()>wanted)list.remove(list.size()-1).remove();int index=0;for(var c:cs){int count=Math.min(MAX_PER_COMPARTMENT,Math.max(1,(int)Math.ceil(Math.sqrt(c.volume())*c.level())));double level=Math.max(.01,Math.min(1,c.level()));int cols=Math.max(1,(int)Math.ceil(Math.sqrt(c.volume())));for(int n=0;n<count&&index<list.size();n++,index++){double u=(n%cols)/(double)Math.max(1,cols-1);double v=(n/cols)/(double)Math.max(1,(int)Math.ceil((double)c.volume()/cols)-1);double x=c.minX()+.15+(c.maxX()-c.minX()+.7)*u;double z=c.minZ()+.15+(c.maxZ()-c.minZ()+.7)*v;double y=c.bottomY()+level*(c.topY()-c.bottomY()+1)-.05;BlockDisplay d=list.get(index);d.teleport(transform(base,ship,x,y,z));float sx=(float)Math.max(.25,Math.min(1.2,(c.maxX()-c.minX()+1.0)/Math.max(1,cols)*1.15));float sz=(float)Math.max(.25,Math.min(1.2,(c.maxZ()-c.minZ()+1.0)/Math.max(1,cols)*1.15));d.setTransformation(new Transformation(new Vector3f(-.5f,-.02f,-.5f),new AxisAngle4f(),new Vector3f(sx,.055f+.16f*(float)level,sz),new AxisAngle4f()));}}if(tick%4==0)emitLeaks(world,base,ship,cs);}
    private BlockDisplay spawn(World world,Location base){return world.spawn(base,BlockDisplay.class,e->{e.setBlock(Material.WATER.createBlockData());e.setBillboard(Display.Billboard.FIXED);e.setInterpolationDuration(2);e.setTeleportDuration(2);e.setPersistent(false);e.setViewRange(64);});}
    private void emitLeaks(World world,Location base,ShipModel ship,List<ShipFloodingManager.CompartmentWater> cs){for(var c:cs){if(c.leaks().isEmpty()||c.level()<.02)continue;int amount=Math.min(3,c.leaks().size());for(int i=0;i<amount;i++){var p=c.leaks().get((int)((tick/4+i)%c.leaks().size()));Location l=transform(base,ship,p.x()+.5,p.y()+.5,p.z()+.5);world.spawnParticle(Particle.BUBBLE,l,3,0.16,.16,.16,.02);if(c.level()>.45)world.spawnParticle(Particle.SPLASH,l,2,.16,.08,.16,.025);}}}
    private Location transform(Location base,ShipModel ship,double x,double y,double z){double r=Math.toRadians(ship.yaw()-ship.origin().getYaw()),cos=Math.cos(r),sin=Math.sin(r);Location l=base.clone().add(x*cos-z*sin,y,x*sin+z*cos);l.setYaw(ship.yaw());return l;}
    public void clear(UUID id){List<BlockDisplay> list=displays.remove(id);if(list!=null)for(BlockDisplay d:list)if(d!=null&&!d.isDead())d.remove();}
    public void clearAll(){Iterator<List<BlockDisplay>>it=displays.values().iterator();while(it.hasNext()){for(BlockDisplay d:it.next())if(d!=null&&!d.isDead())d.remove();it.remove();}}
}
