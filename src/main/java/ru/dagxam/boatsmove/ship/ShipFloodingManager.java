package ru.dagxam.boatsmove.ship;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.*;

/** Compartment flooding with aperture/pressure driven ingress, drainage and bulkhead transfer. */
public final class ShipFloodingManager {
    private static final int MAX_CELLS = 4096;
    private static final int MAX_LEAKS_PER_TICK = 64;
    private static final double FLOW_RATE = 0.020;
    private static final double DRAIN_RATE = 0.0010;
    private static final double BULKHEAD_FLOW_RATE = 0.055;
    private static final double MAX_BULKHEAD_TRANSFER = 0.035;
    private static final double SINK_THRESHOLD = 0.72;
    private static final double FLOOD_TRIM_STRENGTH = 8.0;
    private static final double GRAVITY_FLOW_SCALE = 0.020;
    private static final double MAX_EXTERNAL_FLOW = 0.080;

    private final ShipRegistry registry;
    private final Map<UUID, TopologyCache> topology = new HashMap<>();
    private final Map<UUID, FloodState> floodStates = new HashMap<>();

    public ShipFloodingManager(ShipRegistry registry, ShipDisplayManager ignoredDisplays) { this.registry = registry; }

    public void tick() {
        for (ShipModel ship : registry.all()) {
            if (ship.state() == ShipState.ACTIVE && ship.blockCount() > 0) update(ship);
        }
    }

    private void update(ShipModel ship) {
        Location position = registry.position(ship);
        World world = position.getWorld();
        if (world == null) return;
        long signature = signature(ship);
        TopologyCache cache = topology.get(ship.id());
        FloodState state = floodStates.computeIfAbsent(ship.id(), ignored -> new FloodState());

        if (cache == null) {
            cache = buildTopology(ship, signature);
            topology.put(ship.id(), cache);
            state.reconcile(cache);
        } else if (cache.signature() != signature) {
            TopologyCache rebuilt = buildTopology(ship, signature);
            if (hasBreachedBulkhead(cache, rebuilt)) cache = preserveCompartmentsAfterBreach(cache, rebuilt);
            else { migrateWater(cache, rebuilt, state); cache = rebuilt; }
            topology.put(ship.id(), cache);
            state.reconcile(cache);
        }
        if (cache.compartments().isEmpty()) return;

        int totalVolume = 0, front = 0, rear = 0, left = 0, right = 0;
        for (Compartment c : cache.compartments()) {
            totalVolume += c.cells().size();
            CompartmentState cs = state.compartments.computeIfAbsent(c.seed(), ignored -> new CompartmentState());
            LeakInfo leaks = findLeaks(world, position, ship, cache.hull(), cache.outside(), c);
            if (leaks.count() > 0) applyExternalFlow(cs, c, leaks);
            else if (cs.level > 0) cs.level = Math.max(0, cs.level - DRAIN_RATE);
            front += leaks.front(); rear += leaks.rear(); left += leaks.left(); right += leaks.right();
        }

        transferBetweenCompartments(cache, state);
        double weightedFlood = 0;
        for (Compartment c : cache.compartments()) {
            CompartmentState cs = state.compartments.get(c.seed());
            if (cs != null) weightedFlood += cs.level * c.cells().size();
        }
        double scalar = totalVolume == 0 ? 0 : clamp01(weightedFlood / totalVolume);
        ship.flooding(Math.max(scalar, ship.flooding() * 0.995));
        int sideTotal = Math.max(1, front + rear + left + right);
        ship.floodSides((double) front / sideTotal, (double) rear / sideTotal,
                (double) left / sideTotal, (double) right / sideTotal);
        applyFloodTrim(ship, cache, state);
    }

    /** Applies signed flow through all currently water-exposed breach faces of one compartment. */
    private void applyExternalFlow(CompartmentState cs, Compartment c, LeakInfo leaks) {
        double insideSurface = c.bottomY() + cs.level * c.height();
        double averageExternal = leaks.surface();
        double levelDelta = averageExternal - insideSurface;
        double aperture = Math.min(12.0, Math.max(1.0, leaks.count() / 2.0));
        double pressure = Math.sqrt(Math.min(4.0, Math.abs(levelDelta)));
        double flow = GRAVITY_FLOW_SCALE * aperture * pressure;
        flow = Math.min(MAX_EXTERNAL_FLOW, flow);
        // A breach above the external waterline is effectively dry; do not create water from it.
        if (levelDelta > 0) {
            double intakeFactor = clamp01(averageExternal - c.bottomY());
            flow *= Math.min(1.0, 0.35 + intakeFactor / Math.max(1.0, c.height()));
            cs.level = clamp01(cs.level + flow / Math.max(1.0, c.height()));
        } else if (levelDelta < 0) {
            // Internal head is higher: water drains outward through the same opening.
            double drain = Math.min(cs.level, flow / Math.max(1.0, c.height()));
            cs.level = clamp01(cs.level - drain);
        }
    }

    private boolean hasBreachedBulkhead(TopologyCache oldCache, TopologyCache newCache) {
        Set<Pos> removed = new HashSet<>(oldCache.hull()); removed.removeAll(newCache.hull());
        if (removed.isEmpty() || oldCache.compartments().size() < 2) return false;
        Map<Pos,Integer> owners = compartmentOwners(oldCache.compartments());
        for (Pos hole : removed) {
            Set<Integer> adjacent = new HashSet<>();
            for (Pos d : DIRECTIONS) { Integer owner = owners.get(hole.add(d)); if (owner != null) adjacent.add(owner); }
            if (adjacent.size() >= 2) return true;
        }
        return false;
    }

    private TopologyCache preserveCompartmentsAfterBreach(TopologyCache oldCache, TopologyCache newCache) {
        Set<Pos> removed = new HashSet<>(oldCache.hull()); removed.removeAll(newCache.hull());
        Map<Pos,Integer> owners = compartmentOwners(oldCache.compartments());
        List<BulkheadLink> links = new ArrayList<>();
        for (Pos hole : removed) {
            Set<Integer> adjacent = new HashSet<>();
            for (Pos d : DIRECTIONS) { Integer owner = owners.get(hole.add(d)); if (owner != null) adjacent.add(owner); }
            List<Integer> ids = new ArrayList<>(adjacent);
            for (int i=0;i<ids.size();i++) for (int j=i+1;j<ids.size();j++)
                addLink(links, oldCache.compartments().get(ids.get(i)).seed(), oldCache.compartments().get(ids.get(j)).seed());
        }
        return new TopologyCache(newCache.signature(), newCache.hull(), newCache.outside(), oldCache.compartments(), links);
    }

    private void addLink(List<BulkheadLink> links, Pos a, Pos b) {
        for (BulkheadLink link : links) {
            if ((link.a.equals(a) && link.b.equals(b)) || (link.a.equals(b) && link.b.equals(a))) { link.area++; return; }
        }
        links.add(new BulkheadLink(a,b,1));
    }

    private Map<Pos,Integer> compartmentOwners(List<Compartment> compartments) {
        Map<Pos,Integer> owners = new HashMap<>();
        for (int i=0;i<compartments.size();i++) for (Pos p:compartments.get(i).cells()) owners.put(p,i);
        return owners;
    }

    private void transferBetweenCompartments(TopologyCache cache, FloodState state) {
        for (BulkheadLink link : cache.bulkheads()) {
            Compartment a=findCompartment(cache.compartments(),link.a), b=findCompartment(cache.compartments(),link.b);
            CompartmentState as=state.compartments.get(link.a), bs=state.compartments.get(link.b);
            if(a==null||b==null||as==null||bs==null) continue;
            double delta=as.level-bs.level;
            if(Math.abs(delta)<0.00025) continue;
            Compartment source=delta>0?a:b, target=delta>0?b:a;
            CompartmentState ss=delta>0?as:bs, ts=delta>0?bs:as;
            double sv=source.cells().size(), tv=target.cells().size();
            double aperture=Math.min(8.0,Math.max(1.0,link.area));
            double pressure=Math.sqrt(Math.abs(delta));
            double transfer=Math.min(MAX_BULKHEAD_TRANSFER*aperture*pressure, BULKHEAD_FLOW_RATE*aperture*pressure);
            transfer=Math.min(transfer,ss.level*sv);
            if(transfer<=0) continue;
            ss.level=clamp01(ss.level-transfer/sv); ts.level=clamp01(ts.level+transfer/tv);
        }
    }

    private Compartment findCompartment(List<Compartment> list, Pos seed) {
        for(Compartment c:list) if(c.seed().equals(seed)) return c; return null;
    }

    private void migrateWater(TopologyCache oldCache, TopologyCache newCache, FloodState state) {
        if(oldCache.compartments().isEmpty()||newCache.compartments().isEmpty()){state.compartments.clear();return;}
        Map<Pos,Double> oldWater=new HashMap<>();
        for(Compartment old:oldCache.compartments()){
            CompartmentState os=state.compartments.get(old.seed()); double level=os==null?0:clamp01(os.level);
            for(Pos cell:old.cells()) oldWater.put(cell,level);
        }
        Map<Pos,CompartmentState> migrated=new HashMap<>();
        for(Compartment next:newCache.compartments()){
            double volume=0; for(Pos cell:next.cells()) volume+=oldWater.getOrDefault(cell,0.0);
            CompartmentState ns=new CompartmentState(); ns.level=clamp01(volume/Math.max(1,next.cells().size())); migrated.put(next.seed(),ns);
        }
        state.compartments.clear(); state.compartments.putAll(migrated);
    }

    private LeakInfo findLeaks(World world, Location position, ShipModel ship, Set<Pos> hull, Set<Pos> outside, Compartment compartment) {
        int count=0,front=0,rear=0,left=0,right=0; double surfaceSum=0; List<Pos> points=new ArrayList<>();
        outer: for(Pos cell:compartment.cells()) for(Pos d:DIRECTIONS){
            Pos adjacent=cell.add(d);
            if(hull.contains(adjacent)||!outside.contains(adjacent)) continue;
            WaterSample sample=externalWater(world,position,ship,adjacent); if(!sample.water()) continue;
            count++; surfaceSum+=sample.surface(); points.add(adjacent);
            if(d.z()>0)front++; else if(d.z()<0)rear++; else if(d.x()<0)left++; else if(d.x()>0)right++;
            if(count>=MAX_LEAKS_PER_TICK) break outer;
        }
        return new LeakInfo(count,front,rear,left,right,count==0?Double.NEGATIVE_INFINITY:surfaceSum/count,points);
    }

    private void applyFloodTrim(ShipModel ship, TopologyCache cache, FloodState state) {
        ShipRuntimeState runtime=registry.runtime(ship.id()); if(runtime==null)return;
        double total=0,xMoment=0,zMoment=0;
        for(Compartment c:cache.compartments()){
            CompartmentState cs=state.compartments.get(c.seed()); if(cs==null||cs.level<=0)continue;
            double volume=cs.level*c.cells().size(); total+=volume;
            xMoment+=(c.minX()+c.maxX())*0.5*volume; zMoment+=(c.minZ()+c.maxZ())*0.5*volume;
        }
        if(total<=0.001){runtime.pitch(approach(runtime.pitch(),0,0.10f));runtime.roll(approach(runtime.roll(),0,0.10f));return;}
        double minX=cache.compartments().stream().mapToInt(Compartment::minX).min().orElse(0), maxX=cache.compartments().stream().mapToInt(Compartment::maxX).max().orElse(0);
        double minZ=cache.compartments().stream().mapToInt(Compartment::minZ).min().orElse(0), maxZ=cache.compartments().stream().mapToInt(Compartment::maxZ).max().orElse(0);
        double nx=((xMoment/total)-(minX+maxX)*0.5)/Math.max(1,(maxX-minX)*0.5);
        double nz=((zMoment/total)-(minZ+maxZ)*0.5)/Math.max(1,(maxZ-minZ)*0.5);
        runtime.roll(approach(runtime.roll(),(float)clamp(nx*FLOOD_TRIM_STRENGTH*ship.flooding(),-7,7),0.10f));
        runtime.pitch(approach(runtime.pitch(),(float)clamp(-nz*FLOOD_TRIM_STRENGTH*ship.flooding(),-7,7),0.10f));
        if(ship.flooding()>SINK_THRESHOLD){double severity=(ship.flooding()-SINK_THRESHOLD)/(1-SINK_THRESHOLD);runtime.verticalSpeed(Math.min(runtime.verticalSpeed(),-Math.min(0.085,0.012+severity*0.073)));}
        else if(runtime.verticalSpeed()<0)runtime.verticalSpeed(runtime.verticalSpeed()*0.90);
    }

    public BuoyancyState buoyancyState(ShipModel ship) {
        FloodState state=floodStates.get(ship.id()); TopologyCache cache=topology.get(ship.id());
        if(state==null||cache==null||cache.compartments().isEmpty())return BuoyancyState.EMPTY;
        double totalWater=0,xMoment=0,zMoment=0;
        for(Compartment c:cache.compartments()){
            CompartmentState cs=state.compartments.get(c.seed()); if(cs==null||cs.level<=0)continue;
            double v=cs.level*c.cells().size(); totalWater+=v; xMoment+=(c.minX()+c.maxX())*0.5*v; zMoment+=(c.minZ()+c.maxZ())*0.5*v;
        }
        if(totalWater<=0.001)return BuoyancyState.EMPTY;
        double minX=cache.compartments().stream().mapToInt(Compartment::minX).min().orElse(0),maxX=cache.compartments().stream().mapToInt(Compartment::maxX).max().orElse(0);
        double minZ=cache.compartments().stream().mapToInt(Compartment::minZ).min().orElse(0),maxZ=cache.compartments().stream().mapToInt(Compartment::maxZ).max().orElse(0);
        double cx=(minX+maxX)*0.5,cz=(minZ+maxZ)*0.5;
        return new BuoyancyState(clamp01(totalWater/Math.max(1,hullVolume(cache))),clamp((xMoment/totalWater-cx)/Math.max(1,(maxX-minX)*0.5),-1,1),clamp((zMoment/totalWater-cz)/Math.max(1,(maxZ-minZ)*0.5),-1,1));
    }

    private int hullVolume(TopologyCache cache){int v=0;for(Compartment c:cache.compartments())v+=c.cells().size();return Math.max(1,v);}
    public record BuoyancyState(double floodedFraction,double lateralCenter,double longitudinalCenter){private static final BuoyancyState EMPTY=new BuoyancyState(0,0,0);}

    private TopologyCache buildTopology(ShipModel ship,long signature){
        Set<Pos> hull=new HashSet<>(); int minX=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,minY=Integer.MAX_VALUE,maxY=Integer.MIN_VALUE,minZ=Integer.MAX_VALUE,maxZ=Integer.MIN_VALUE;
        for(ShipBlock b:ship.blocks()){Pos p=new Pos(b.x(),b.y(),b.z());hull.add(p);minX=Math.min(minX,p.x());maxX=Math.max(maxX,p.x());minY=Math.min(minY,p.y());maxY=Math.max(maxY,p.y());minZ=Math.min(minZ,p.z());maxZ=Math.max(maxZ,p.z());}
        if(hull.isEmpty())return new TopologyCache(signature,hull,Set.of(),List.of(),List.of());
        Set<Pos> outside=floodOutside(hull,minX,maxX,minY,maxY,minZ,maxZ),unvisited=new HashSet<>();int cells=0;
        for(int x=minX+1;x<maxX&&cells<MAX_CELLS;x++)for(int y=minY+1;y<maxY&&cells<MAX_CELLS;y++)for(int z=minZ+1;z<maxZ&&cells<MAX_CELLS;z++){Pos p=new Pos(x,y,z);if(!hull.contains(p)&&!outside.contains(p)){unvisited.add(p);cells++;}}
        List<Compartment> compartments=new ArrayList<>();
        while(!unvisited.isEmpty()){
            Pos seed=unvisited.stream().min(Comparator.comparingInt(Pos::y).thenComparingInt(Pos::x).thenComparingInt(Pos::z)).orElseThrow();
            Set<Pos> set=new HashSet<>();Queue<Pos> q=new ArrayDeque<>();q.add(seed);unvisited.remove(seed);
            while(!q.isEmpty()){Pos p=q.poll();set.add(p);for(Pos d:DIRECTIONS){Pos n=p.add(d);if(unvisited.remove(n))q.add(n);}}
            compartments.add(new Compartment(seed,set));
        }
        return new TopologyCache(signature,hull,outside,compartments,List.of());
    }

    private Set<Pos> floodOutside(Set<Pos> hull,int minX,int maxX,int minY,int maxY,int minZ,int maxZ){
        int loX=minX-1,hiX=maxX+1,loY=minY-1,hiY=maxY+1,loZ=minZ-1,hiZ=maxZ+1;Set<Pos> outside=new HashSet<>();Queue<Pos> q=new ArrayDeque<>();Pos start=new Pos(loX,loY,loZ);outside.add(start);q.add(start);
        while(!q.isEmpty()&&outside.size()<MAX_CELLS*2){Pos p=q.poll();for(Pos d:DIRECTIONS){Pos n=p.add(d);if(n.x()<loX||n.x()>hiX||n.y()<loY||n.y()>hiY||n.z()<loZ||n.z()>hiZ)continue;if(hull.contains(n)||!outside.add(n))continue;q.add(n);}}
        return outside;
    }

    private WaterSample externalWater(World world,Location position,ShipModel ship,Pos local){
        Pos p=rotateLocal(local,ship.yaw()-ship.origin().getYaw());int x=(int)Math.floor(position.getX()+p.x()+0.5),y=(int)Math.floor(position.getY()+local.y()),z=(int)Math.floor(position.getZ()+p.z()+0.5);
        if(!world.isChunkLoaded(x>>4,z>>4))return WaterSample.NONE;
        for(int sy=y+2;sy>=y-2;sy--)if(world.getBlockAt(x,sy,z).getType()==Material.WATER)return new WaterSample(true,sy+1.0);
        return WaterSample.NONE;
    }
    private Pos rotateLocal(Pos p,double degrees){return switch(Math.floorMod((int)Math.round(degrees/90.0),4)){case 1->new Pos(-p.z(),p.y(),p.x());case 2->new Pos(-p.x(),p.y(),-p.z());case 3->new Pos(p.z(),p.y(),-p.x());default->p;};}
    private long signature(ShipModel ship){long r=1125899906842597L;for(ShipBlock b:ship.blocks()){r=31*r+b.x();r=31*r+b.y();r=31*r+b.z();}return r;}

    public List<CompartmentWater> compartmentWater(ShipModel ship){
        FloodState state=floodStates.get(ship.id());TopologyCache cache=topology.get(ship.id());if(state==null||cache==null)return List.of();List<CompartmentWater> result=new ArrayList<>();
        for(Compartment c:cache.compartments()){CompartmentState cs=state.compartments.get(c.seed());if(cs==null||cs.level<=0.001)continue;List<LeakPoint> leaks=new ArrayList<>();
            for(Pos cell:c.cells())for(Pos d:DIRECTIONS){Pos a=cell.add(d);if(!cache.hull().contains(a)&&cache.outside().contains(a)){leaks.add(new LeakPoint(a.x(),a.y(),a.z()));if(leaks.size()>=12)break;}if(leaks.size()>=12)break;}
            result.add(new CompartmentWater(c.seed().x(),c.seed().y(),c.seed().z(),cs.level,c.cells().size(),c.bottomY(),c.topY(),c.minX(),c.maxX(),c.minZ(),c.maxZ(),List.copyOf(leaks)));
        } return List.copyOf(result);
    }
    public record CompartmentWater(int seedX,int seedY,int seedZ,double level,int volume,int bottomY,int topY,int minX,int maxX,int minZ,int maxZ,List<LeakPoint> leaks){public int width(){return Math.max(1,maxX-minX+1);}public int depth(){return Math.max(1,maxZ-minZ+1);}}
    public record LeakPoint(int x,int y,int z){}
    public double frontLeak(ShipModel ship){return ship.floodFront();} public double rearLeak(ShipModel ship){return ship.floodRear();} public double leftLeak(ShipModel ship){return ship.floodLeft();} public double rightLeak(ShipModel ship){return ship.floodRight();}

    private static double clamp01(double v){return Math.max(0,Math.min(1,v));} private static double clamp(double v,double min,double max){return Math.max(min,Math.min(max,v));}
    private static float approach(float c,float t,float a){if(c<t)return Math.min(t,c+a);return Math.max(t,c-a);}
    private static final List<Pos> DIRECTIONS=List.of(new Pos(1,0,0),new Pos(-1,0,0),new Pos(0,1,0),new Pos(0,-1,0),new Pos(0,0,1),new Pos(0,0,-1));
    private record Pos(int x,int y,int z){Pos add(Pos p){return new Pos(x+p.x,y+p.y,z+p.z);}}
    private record Compartment(Pos seed,Set<Pos> cells){int bottomY(){return cells.stream().mapToInt(Pos::y).min().orElse(seed.y);}int topY(){return cells.stream().mapToInt(Pos::y).max().orElse(seed.y);}int minX(){return cells.stream().mapToInt(Pos::x).min().orElse(seed.x);}int maxX(){return cells.stream().mapToInt(Pos::x).max().orElse(seed.x);}int minZ(){return cells.stream().mapToInt(Pos::z).min().orElse(seed.z);}int maxZ(){return cells.stream().mapToInt(Pos::z).max().orElse(seed.z);}int height(){return Math.max(1,topY()-bottomY()+1);}}
    private record TopologyCache(long signature,Set<Pos> hull,Set<Pos> outside,List<Compartment> compartments,List<BulkheadLink> bulkheads){}
    private record LeakInfo(int count,int front,int rear,int left,int right,double surface,List<Pos> points){}
    private record WaterSample(boolean water,double surface){private static final WaterSample NONE=new WaterSample(false,Double.NEGATIVE_INFINITY);}
    private static final class BulkheadLink{private final Pos a,b;private int area;BulkheadLink(Pos a,Pos b,int area){this.a=a;this.b=b;this.area=area;}}
    private static final class CompartmentState{private double level;}
    private static final class FloodState{private final Map<Pos,CompartmentState> compartments=new HashMap<>();private void reconcile(TopologyCache cache){Set<Pos> active=new HashSet<>();for(Compartment c:cache.compartments())active.add(c.seed());compartments.keySet().removeIf(seed->!active.contains(seed));}}
}
