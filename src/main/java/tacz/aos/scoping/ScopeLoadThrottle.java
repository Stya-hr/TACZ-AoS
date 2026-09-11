package tacz.aos.scoping;

/** Per-player server-clock limits. Contains no target queue and is retained while the player unscopes. */
public final class ScopeLoadThrottle {
    private long checked=Long.MIN_VALUE, started=Long.MIN_VALUE;
    public boolean tryCheck(long tick,int interval) {
        if(!elapsed(checked,tick,interval))return false;
        checked=tick;
        return true;
    }
    public boolean canStart(long tick,int interval) {return elapsed(started,tick,interval);}
    public void started(long tick) {started=tick;}
    private static boolean elapsed(long previous,long tick,int interval) {
        return previous==Long.MIN_VALUE || tick<previous || tick-previous>=interval;
    }
}
