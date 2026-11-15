package com.example.splitease.ui.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewTreeObserver;
import com.example.splitease.R;
import com.google.android.material.card.MaterialCardView;

/**
 * Frosted card: paints the blurred window photograph behind the card's translucent fill, aligned
 * with the Activity backdrop so the glass appears to blur what is underneath it. Presentation only.
 */
public class GlassCardView extends MaterialCardView {
    private static Bitmap blurred;
    private final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG|Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix=new Matrix();
    private final Path clip=new Path();
    private final int[] location=new int[2];
    private int drawnX=Integer.MIN_VALUE,drawnY=Integer.MIN_VALUE;
    // Scrolling and transition animators move the card without redrawing it; keep the backdrop anchored.
    private final ViewTreeObserver.OnPreDrawListener anchor=() -> {
        getLocationInWindow(location);
        if(location[0]!=drawnX||location[1]!=drawnY) invalidate();
        return true;
    };

    public GlassCardView(Context context) { this(context,null); }
    public GlassCardView(Context context,AttributeSet attrs) { this(context,attrs,com.google.android.material.R.attr.materialCardViewStyle); }
    public GlassCardView(Context context,AttributeSet attrs,int style) { super(context,attrs,style); setWillNotDraw(false); }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); getViewTreeObserver().addOnPreDrawListener(anchor); }
    @Override protected void onDetachedFromWindow() { getViewTreeObserver().removeOnPreDrawListener(anchor); super.onDetachedFromWindow(); }

    @Override public void draw(Canvas canvas) {
        Bitmap backdrop=backdrop(getContext());
        View root=getRootView();
        if(backdrop!=null&&root.getWidth()>0&&root.getHeight()>0) {
            getLocationInWindow(location); drawnX=location[0]; drawnY=location[1];
            // Same centre-crop as the Activity's backdrop ImageView.
            float scale=Math.max(root.getWidth()/(float)backdrop.getWidth(),root.getHeight()/(float)backdrop.getHeight());
            matrix.setScale(scale,scale);
            matrix.postTranslate((root.getWidth()-backdrop.getWidth()*scale)/2f-drawnX,(root.getHeight()-backdrop.getHeight()*scale)/2f-drawnY);
            clip.rewind(); clip.addRoundRect(0,0,getWidth(),getHeight(),getRadius(),getRadius(),Path.Direction.CW);
            int save=canvas.save(); canvas.clipPath(clip); canvas.drawBitmap(backdrop,matrix,paint); canvas.restoreToCount(save);
        }
        super.draw(canvas);
    }

    private static synchronized Bitmap backdrop(Context context) {
        if(blurred==null) {
            BitmapFactory.Options options=new BitmapFactory.Options(); options.inSampleSize=8; options.inScaled=false;
            Bitmap small=BitmapFactory.decodeResource(context.getApplicationContext().getResources(),R.drawable.bg_friends,options);
            if(small==null) return null;
            int w=small.getWidth(),h=small.getHeight(); int[] pixels=new int[w*h],scratch=new int[w*h];
            small.getPixels(pixels,0,w,0,0,w,h);
            for(int pass=0;pass<3;pass++) { box(pixels,scratch,w,h,true); box(scratch,pixels,w,h,false); }
            blurred=Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888);
        }
        return blurred;
    }

    /** One box-blur pass over a tiny thumbnail; three passes approximate a Gaussian. */
    private static void box(int[] in,int[] out,int w,int h,boolean horizontal) {
        final int radius=3;
        int lines=horizontal?h:w,length=horizontal?w:h;
        for(int line=0;line<lines;line++) for(int i=0;i<length;i++) {
            int r=0,g=0,b=0,n=0;
            for(int k=Math.max(0,i-radius);k<=Math.min(length-1,i+radius);k++) {
                int c=in[horizontal?line*w+k:k*w+line]; r+=(c>>16)&255; g+=(c>>8)&255; b+=c&255; n++;
            }
            out[horizontal?line*w+i:i*w+line]=0xFF000000|((r/n)<<16)|((g/n)<<8)|(b/n);
        }
    }
}
