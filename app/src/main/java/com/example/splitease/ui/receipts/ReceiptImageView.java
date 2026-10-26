package com.example.splitease.ui.receipts;
import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.*;
import androidx.annotation.Nullable;

/** Width-fit, pan/zoom receipt canvas; long images retain their readable raster width. */
public final class ReceiptImageView extends View {
 private Bitmap bitmap;private float zoom=1,panX,panY,lastX,lastY;private final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);private final ScaleGestureDetector scale;
 public ReceiptImageView(Context context,@Nullable AttributeSet attrs){super(context,attrs);setFocusable(true);scale=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){@Override public boolean onScale(ScaleGestureDetector detector){zoom=Math.max(1,Math.min(5,zoom*detector.getScaleFactor()));invalidate();return true;}});}
 public void image(Bitmap image){if(bitmap!=image){bitmap=image;zoom=1;panX=panY=0;invalidate();}}
 public void zoomBy(float factor){zoom=Math.max(1,Math.min(5,zoom*factor));invalidate();}
 private float fit(){return bitmap==null||getWidth()==0?1:getWidth()/(float)bitmap.getWidth();}
 @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);if(bitmap==null)return;float factor=fit()*zoom;panX=Math.min(0,Math.max(getWidth()-bitmap.getWidth()*factor,panX));panY=Math.min(0,Math.max(getHeight()-bitmap.getHeight()*factor,panY));canvas.save();canvas.translate(panX,panY);canvas.scale(factor,factor);canvas.drawBitmap(bitmap,0,0,paint);canvas.restore();}
 @Override public boolean onTouchEvent(android.view.MotionEvent event){if(bitmap==null)return false;scale.onTouchEvent(event);switch(event.getActionMasked()){case MotionEvent.ACTION_DOWN:lastX=event.getX();lastY=event.getY();getParent().requestDisallowInterceptTouchEvent(true);return true;case MotionEvent.ACTION_MOVE:if(!scale.isInProgress()){panX+=event.getX()-lastX;panY+=event.getY()-lastY;invalidate();}lastX=event.getX();lastY=event.getY();return true;case MotionEvent.ACTION_UP:performClick();return true;default:return true;}}
 @Override public boolean performClick(){super.performClick();return true;}
}
