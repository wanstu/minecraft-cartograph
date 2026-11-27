package com.wanstu.cartograph.v4;

import java.util.Objects;

/** View choices change presentation only, never the geographic source. */
public final class MapViewOptions {
    private MapViewOptions(){}
    public enum Choice {
        SATELLITE_MODERN,SATELLITE_ANCIENT,HAND_MODERN,HAND_ANCIENT,HAND_ANIME,
        FLAT,TILT_60,TILT_45,TILT_30,ROTATE_LEFT,ROTATE_RIGHT,NORTH_UP;
        public MapRaster.Style style(){
            return switch(this){
                case SATELLITE_MODERN->MapRaster.Style.SATELLITE_MODERN;
                case SATELLITE_ANCIENT->MapRaster.Style.SATELLITE_ANCIENT;
                case HAND_MODERN->MapRaster.Style.HAND_MODERN;
                case HAND_ANCIENT->MapRaster.Style.HAND_ANCIENT;
                case HAND_ANIME->MapRaster.Style.HAND_ANIME;
                default->null;
            };
        }
        public int tilt(){
            return switch(this){case FLAT->90;case TILT_60->60;case TILT_45->45;case TILT_30->30;default->0;};
        }
        public boolean enabled(boolean regionSelection){return !regionSelection||tilt()==0||tilt()==90;}
        public boolean selected(MapRaster.Style style,MapCamera camera){
            return style()!=null?style()==style:tilt()!=0?camera.elevationDegrees()==tilt():this==NORTH_UP&&camera.yawDegrees()==0;
        }
    }
    private static final Choice[] STYLES={Choice.SATELLITE_MODERN,Choice.SATELLITE_ANCIENT,Choice.HAND_MODERN,Choice.HAND_ANCIENT,Choice.HAND_ANIME};
    private static final Choice[] TILTS={Choice.FLAT,Choice.TILT_60,Choice.TILT_45,Choice.TILT_30};
    public record Selection(MapRaster.Style style,MapCamera camera){
        public Selection{Objects.requireNonNull(style);Objects.requireNonNull(camera);}
    }
    public static Selection apply(Selection current,Choice choice,boolean regionSelection){
        Objects.requireNonNull(choice);
        if(!choice.enabled(regionSelection))return current;
        var camera=current.camera();
        var style=choice.style();
        if(style!=null)return style==current.style()?current:new Selection(style,camera);
        if(choice.tilt()!=0)return new Selection(current.style(),camera.withAngles(camera.yawDegrees(),choice.tilt()));
        double yaw=switch(choice){case ROTATE_LEFT->camera.yawDegrees()-45;case ROTATE_RIGHT->camera.yawDegrees()+45;case NORTH_UP->0;default->throw new IllegalArgumentException("View choice");};
        return new Selection(current.style(),camera.withAngles(yaw,camera.elevationDegrees()));
    }
    /** Bounded popup geometry; hit and render use the same row rectangles. */
    public record MenuLayout(MapHudLayout.Rect bounds,int header,int rows,int first){
        public MapHudLayout.Rect row(int index){
            if(index<0||index>=rows)throw new IndexOutOfBoundsException(index);
            return new MapHudLayout.Rect(bounds.x(),bounds.y()+header+index*18,bounds.w(),18);
        }
        public Choice choiceAt(double x,double y){
            if(!bounds.contains(x,y)||y<bounds.y()+header)return null;
            int index=(int)((y-bounds.y()-header)/18);
            return index>=rows?null:Choice.values()[first+index];
        }
    }
    public static MenuLayout menu(int width,int height,int x,int y,int first){
        if(width<1||height<1)throw new IllegalArgumentException("Viewport");
        int w=Math.min(188,width),header=Math.min(22,height);
        int count=Choice.values().length,rows=Math.min(count,(height-header)/18),h=header+rows*18;
        x=Math.max(0,Math.min(x,width-w));y=Math.max(0,Math.min(y,height-h));
        first=Math.max(0,Math.min(first,count-rows));
        return new MenuLayout(new MapHudLayout.Rect(x,y,w,h),header,rows,first);
    }
    public static Choice styleAt(int index){return STYLES[index];}
    public static Choice nextTilt(MapCamera camera){
        for(int i=0;i<TILTS.length;i++)if(camera.elevationDegrees()==TILTS[i].tilt())return TILTS[(i+1)%TILTS.length];
        return Choice.FLAT;
    }
}
