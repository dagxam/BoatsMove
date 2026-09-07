package ru.dagxam.boatsmove.ship;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

/** Simulates compartment-based water ingress, transfer and sinking. */
public final class ShipFloodingManager {
    private static final int MAX_CELLS=4096, MAX_LEAKS_PER_TICK=64;
    private static final double FLOW_RATE=0.018,DRAIN_RATE=0.0010,SINK_THRESHOLD=0.72,MAX_WATER_LEVEL_STEP=0.035;
    private static final double COMPARTMENT_TRANSFER_RATE=0.045, MAX_TRANSFER_PER_TICK=0.025;
    private final ShipRegistry registry;
    private final Map<UUID,TopologyCache> topology=new HashMap<>();
    private final Map<UUID,FloodState> floodStates=new HashMap<>();
    public ShipFloodingManager(ShipRegistry registry,ShipDisplayManager ignoredDisplays){this.registry=registry;}
    public void tick(){for(ShipModel ship:registry.all())if(ship.state()==ShipState.ACTIVE&&ship.blockCount()>0)update(ship);}
    private void update(ShipModel ship){
        Location position=registry.position(ship);World world=position.getWorld();if(world==null)return;
        long signature=signature(ship);TopologyCache cache=topology.compute(ship.id(),(id,old)->old==null||old.signature!=signature?buildTopology(ship,signature):old);
        if(cache.compartments.isEmpty()){ship.flooding(Math.max(0,ship.flooding()-DRAIN_RATE));ship.floodSides(0,0,0,0);return;}
        FloodState state=floodStates.computeIfAbsent(ship.id(),ignored->new FloodState());state.reconcile(cache);
        int totalVolume=0,frontLeaks=0,rearLeaks=0,leftLeaks=0,rightLeaks=0;double weightedFlood=0;
        for(Compartment c:cache.compartments){
            totalVolume+=c.cells.size();CompartmentState cs=state.compartments.computeIfAbsent(c.seed,ignored->new CompartmentState());
            LeakInfo leaks=findLeaks(world,position,ship,cache.hull,c);double current=cs.level;
            if(leaks.count>0){double headroom=Math.max(0,leaks.surface-c.bottomY()-current*c.height());double pressure=Math.max(0,Math.min(1,headroom/Math.max(1,c.height())));double volumeFactor=1.0/Math.max(1,Math.sqrt(c.cells.size()));double flow=Math.min(MAX_WATER_LEVEL_STEP,leaks.count*FLOW_RATE*volumeFactor*(0.25+pressure));cs.level=Math.min(1,current+flow);}else if(current>0)cs.level=Math.max(0,current-DRAIN_RATE);
            weightedFlood+=cs.level*c.cells.size();frontLeaks+=leaks.front;rearLeaks+=leaks.rear;leftLeaks+=leaks.left;rightLeaks+=leaks.right;
        }
        transferBetweenCompartments(cache,state);
        weightedFlood=0;for(Compartment c:cache.compartments){CompartmentState cs=state.compartments.get(c.seed);if(cs!=null)weightedFlood+=cs.level*c.cells.size();}
        double scalar=totalVolume==0?0:weightedFlood/totalVolume;ship.flooding(Math.max(scalar,ship.flooding()*0.995));int sideTotal=Math.max(1,frontLeaks+rearLeaks+leftLeaks+rightLeaks);ship.floodSides((double)frontLeaks/sideTotal,(double)rearLeaks/sideTotal,(double)leftLeaks/sideTotal,(double)rightLeaks/sideTotal);applySinking(ship,ship.floodFront(),ship.floodRear(),ship.floodLeft(),ship.floodRight());
    }
    private void transferBetweenCompartments(TopologyCache cache,FloodState state){
        for(int i=0;i<cache.compartments.size();i++)for(int j=i+1;j<cache.compartments.size();j++){
            Compartment a=cache.compartments.get(i),b=cache.compartments.get(j);CompartmentState as=state.compartments.get(a.seed),bs=state.compartments.get(b.seed);if(as==null||bs==null)continue;
            int openings=sharedOpenings(a,b);if(openings<=0)continue;
            double levelDiff=as.level-bs.level;if(Math.abs(levelDiff)<0.002)continue;
            double sourceLevel=Math.max(as.level,bs.level), transfer=Math.min(MAX_TRANSFER_PER_TICK,Math.abs(levelDiff)*COMPARTMENT_TRANSFER_RATE*openings);
            double sourceVolume=sourceLevel==as.level?a.cells.size():b.cells.size();double targetVolume=sourceLevel==as.level?b.cells.size():a.cells.size();if(sourceVolume<=0||targetVolume<=0)continue;
            if(sourceLevel==as.level){double amount=Math.min(as.level,transfer/sourceVolume);as.level=Math.max(0,as.level-amount);bs.level=Math.min(1,bs.level+amount*sourceVolume/targetVolume);}
            else{double amount=Math.min(bs.level,transfer/sourceVolume);bs.level=Math.max(0,bs.level-amount);as.level=Math.min(1,as.level+amount*sourceVolume/targetVolume);}
        }
    }
    private int sharedOpenings(Compartment a,Compartment b){
        Set<Pos> other=b.cells;int count=0;
        for(Pos p:a.cells)for(Pos d:HORIZONTAL_AND_VERTICAL){if(other.contains(p.add(d))){count++;if(count>=8)return count;}}
        return count;
    }
    private LeakInfo findLeaks(World world,Location position,ShipModel ship,Set<Pos> hull,Compartment c){int leaks=0,front=0,rear=0,left=0,right=0;double surfaceSum=0;int samples=0;List<Pos> points=new ArrayList<>();outer:for(Pos cell:c.cells)for(Pos d:DIRECTIONS){Pos o=cell.add(d);if(hull.contains(o))continue;WaterSample s=externalWater(world,position,ship,o);if(!s.water)continue;leaks++;surfaceSum+=s.surface;samples++;points.add(o);if(d.z>0)front++;else if(d.z<0)rear++;else if(d.x<0)left++;else if(d.x>0)right++;if(leaks>=MAX_LEAKS_PER_TICK)break outer;}return new LeakInfo(leaks,front,rear,left,right,samples==0?Double.NEGATIVE_INFINITY:surfaceSum/samples,points);}
    private void applySinking(ShipModel ship,double front,double rear,double left,double right){ShipRuntimeState runtime=registry.runtime(ship.id());if(runtime==null)return;double flood=ship.flooding();if(flood<=SINK_THRESHOLD){if(runtime.verticalSpeed()<0)runtime.verticalSpeed(runtime.verticalSpeed()*0.90);return;}double severity=(flood-SINK_THRESHOLD)/(1-SINK_THRESHOLD);runtime.verticalSpeed(Math.min(runtime.verticalSpeed(),-Math.min(0.085,0.012+severity*0.073)));runtime.pitch(approach(runtime.pitch(),(float)((rear-front)*10*severity),0.12f));runtime.roll(approach(runtime.roll(),(float)((left-right)*10*severity),0.12f));}
    private TopologyCache buildTopology(ShipModel ship,long signature){Set<Pos> hull=new HashSet<>();int minX=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,minY=Integer.MAX_VALUE,maxY=Integer.MIN_VALUE,minZ=Integer.MAX_VALUE,maxZ=Integer.MIN_VALUE;for(ShipBlock b:ship.blocks()){Pos p=new Pos(b.x(),b.y(),b.z());hull.add(p);minX=Math.min(minX,p.x);maxX=Math.max(maxX,p.x);minY=Math.min(minY,p.y);maxY=Math.max(maxY,p.y);minZ=Math.min(minZ,p.z);maxZ=Math.max(maxZ,p.z);}Set<Pos> outside=floodOutside(hull,minX,maxX,minY,maxY,minZ,maxZ),unvisited=new HashSet<>();int cellCount=0;for(int x=minX+1;x<maxX&&cellCount<MAX_CELLS;x++)for(int y=minY+1;y<maxY&&cellCount<MAX_CELLS;y++)for(int z=minZ+1;z<maxZ&&cellCount<MAX_CELLS;z++){Pos p=new Pos(x,y,z);if(!hull.contains(p)&&!outside.contains(p)){unvisited.add(p);cellCount++;}}List<Compartment> compartments=new ArrayList<>();while(!unvisited.isEmpty()){Pos seed=unvisited.stream().min(Comparator.comparingInt((Pos p)->p.y).thenComparingInt(p->p.x).thenComparingInt(p->p.z)).orElseThrow();Set<Pos> cells=new HashSet<>();Queue<Pos> q=new ArrayDeque<>();q.add(seed);unvisited.remove(seed);while(!q.isEmpty()){Pos c=q.poll();cells.add(c);for(Pos d:DIRECTIONS){Pos n=c.add(d);if(unvisited.remove(n))q.add(n);}}compartments.add(new Compartment(seed,cells));}return new TopologyCache(signature,hull,compartments);}
    private Set<Pos> floodOutside(Set<Pos> hull,int minX,int maxX,int minY,int maxY,int minZ,int maxZ){int loX=minX-1,hiX=maxX+1,loY=minY-1,hiY=maxY+1,loZ=minZ-1,hiZ=maxZ+1;Set<Pos> outside=new HashSet<>();Queue<Pos> q=new ArrayDeque<>();Pos start=new Pos(loX,loY,loZ);outside.add(start);q.add(start);while(!q.isEmpty()&&outside.size()<MAX_CELLS*2){Pos c=q.poll();for(Pos d:DIRECTIONS){Pos n=c.add(d);if(n.x<loX||n.x>hiX||n.y<loY||n.y>hiY||n.z<loZ||n.z>hiZ)continue;if(hull.contains(n)||!outside.add(n))continue;q.add(n);}}return outside;}
    private WaterSample externalWater(World world,Location position,ShipModel ship,Pos local){Pos transformed=rotateLocal(local,ship.yaw()-ship.origin().getYaw());int x=(int)Math.floor(position.getX()+transformed.x+0.5),y=(int)Math.floor(position.getY()+local.y),z=(int)Math.floor(position.getZ()+transformed.z+0.5);if(!world.isChunkLoaded(x>>4,z>>4))return WaterSample.NONE;for(int sy=y+2;sy>=y-2;sy--)if(world.getBlockAt(x,sy,z).getType()==Material.WATER)return new WaterSample(true,sy+1.0);return WaterSample.NONE;}
    private Pos rotateLocal(Pos p,double degrees){return switch(Math.floorMod((int)Math.round(degrees/90.0),4)){case 1->new Pos(-p.z,p.y,p.x);case 2->new Pos(-p.x,p.y,-p.z);case 3->new Pos(p.z,p.y,-p.x);default->p;};}
    private long signature(ShipModel ship){long r=1125899906842597L;for(ShipBlock b:ship.blocks()){r=31*r+b.x();r=31*r+b.y();r=31*r+b.z();}return r;}
    public List<CompartmentWater> compartmentWater(ShipModel ship){FloodState state=floodStates.get(ship.id());TopologyCache cache=topology.get(ship.id());if(state==null||cache==null)return List.of();List<CompartmentWater> result=new ArrayList<>();for(Compartment c:cache.compartments){CompartmentState cs=state.compartments.get(c.seed);if(cs==null||cs.level<=0.001)continue;List<LeakPoint> leaks=new ArrayList<>();for(Pos cell:c.cells)for(Pos d:DIRECTIONS){Pos o=cell.add(d);if(!cache.hull.contains(o)){leaks.add(new LeakPoint(o.x,o.y,o.z));if(leaks.size()>=12)break;}}result.add(new CompartmentWater(c.seed.x,c.seed.y,c.seed.z,cs.level,c.cells.size(),c.bottomY(),c.topY(),c.minX(),c.maxX(),c.minZ(),c.maxZ(),List.copyOf(leaks));}return List.copyOf(result);}
    public record CompartmentWater(int seedX,int seedY,int seedZ,double level,int volume,int bottomY,int topY,int minX,int maxX,int minZ,int maxZ,List<LeakPoint> leaks){public int width(){return Math.max(1,maxX-minX+1);}public int depth(){return Math.max(1,maxZ-minZ+1);}}
    public record LeakPoint(int x,int y,int z){}
    public double frontLeak(ShipModel ship){return ship.floodFront();}public double rearLeak(ShipModel ship){return ship.floodRear();}public double leftLeak(ShipModel ship){return ship.floodLeft();}public double rightLeak(ShipModel ship){return ship.floodRight();}
    private static float approach(float current,float target,float amount){return current<target?Math.min(target,current+amount):Math.max(target,current-amount);}
    private static final List<Pos>DIRECTIONS=List.of(new Pos(1,0,0),new Pos(-1,0,0),new Pos(0,1,0),new Pos(0,-1,0),new Pos(0,0,1),new Pos(0,0,-1));
    private static final List<Pos> HORIZONTAL_AND_VERTICAL=DIRECTIONS;
    private record Pos(int x,int y,int z){Pos add(Pos o){return new Pos(x+o.x,y+o.y,z+o.z);}}
    private record Compartment(Pos seed,Set<Pos> cells){int bottomY(){return cells.stream().mapToInt(p->p.y).min().orElse(seed.y);}int topY(){return cells.stream().mapToInt(p->p.y).max().orElse(seed.y);}int minX(){return cells.stream().mapToInt(p->p.x).min().orElse(seed.x);}int maxX(){return cells.stream().mapToInt(p->p.x).max().orElse(seed.x);}int minZ(){return cells.stream().mapToInt(p->p.z).min().orElse(seed.z);}int maxZ(){return cells.stream().mapToInt(p->p.z).max().orElse(seed.z);}int height(){return Math.max(1,topY()-bottomY()+1);}}
    private record TopologyCache(long signature,Set<Pos> hull,List<Compartment> compartments){}
    private record LeakInfo(int count,int front,int rear,int left,int right,double surface,List<Pos> points){}
    private record WaterSample(boolean water,double surface){private static final WaterSample NONE=new WaterSample(false,Double.NEGATIVE_INFINITY);}
    private static final class CompartmentState{private double level;}
    private static final class FloodState{private final Map<Pos,CompartmentState> compartments=new HashMap<>();private void reconcile(TopologyCache cache){Set<Pos> active=new HashSet<>();for(Compartment c:cache.compartments)active.add(c.seed);compartments.keySet().removeIf(seed->!active.contains(seed));}}
}
