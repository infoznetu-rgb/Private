package sk.preplatok.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class PhotoQuality(val blur: Boolean, val glare: Boolean) {
    val warnings get() = listOfNotNull(if(blur) "Fotografia môže byť rozmazaná. Zaostrite na číslice." else null, if(glare) "Na snímke môže byť odlesk. Zmeňte uhol alebo osvetlenie." else null)
}
internal data class Gray(val width: Int,val height: Int,val pixels: IntArray)
internal object ImageAnalysis {
    fun gray(bitmap: Bitmap, limit: Int = 1200): Gray {
        val scale = min(1f, limit.toFloat()/max(bitmap.width,bitmap.height))
        val b = if(scale < 1) Bitmap.createScaledBitmap(bitmap,max(1,(bitmap.width*scale).toInt()),max(1,(bitmap.height*scale).toInt()),true) else bitmap
        val p = IntArray(b.width*b.height); b.getPixels(p,0,b.width,0,0,b.width,b.height)
        for(i in p.indices) { val c=p[i]; p[i]=(Color.red(c)*77 + Color.green(c)*150 + Color.blue(c)*29) shr 8 }
        if(b !== bitmap) b.recycle()
        return Gray(if(scale < 1) max(1,(bitmap.width*scale).toInt()) else bitmap.width,if(scale < 1) max(1,(bitmap.height*scale).toInt()) else bitmap.height,p)
    }
    fun quality(g: Gray): PhotoQuality {
        var sum=0.0; var sumSq=0.0; var n=0; var clipped=0
        val left=g.width/5; val right=g.width*4/5; val top=g.height/4; val bottom=g.height*3/4
        for(y in max(1,top) until min(bottom,g.height-1)) for(x in max(1,left) until min(right,g.width-1)) {
            val i=y*g.width+x; val lap=4*g.pixels[i]-g.pixels[i-1]-g.pixels[i+1]-g.pixels[i-g.width]-g.pixels[i+g.width]
            sum+=lap; sumSq+=lap.toDouble()*lap; n++; if(g.pixels[i]>=250) clipped++
        }
        val variance=if(n>0) sumSq/n-(sum/n)*(sum/n) else 0.0
        return PhotoQuality(variance<65, n>0 && clipped.toFloat()/n>0.12f)
    }
    fun adjusted(bitmap: Bitmap, binary: Boolean): Bitmap {
        val g=gray(bitmap,2000)
        val histogram=IntArray(256);g.pixels.forEach { histogram[it]++ }
        fun percentile(target: Int): Int { var count=0;for(i in histogram.indices){count+=histogram[i];if(count>=target)return i};return 255 }
        val lo=percentile(g.pixels.size/50);val hi=percentile(g.pixels.size*49/50).coerceAtLeast(lo+1)
        val threshold=otsu(g)
        val pixels=IntArray(g.pixels.size) { i -> val v=if(binary) { if(g.pixels[i]<threshold) 0 else 255 } else ((g.pixels[i]-lo)*255/(hi-lo)).coerceIn(0,255); Color.rgb(v,v,v) }
        return Bitmap.createBitmap(pixels,g.width,g.height,Bitmap.Config.ARGB_8888)
    }
    fun otsu(g: Gray): Int {
        val hist=IntArray(256);g.pixels.forEach { hist[it]++ }
        val total=g.pixels.size;val sum=hist.indices.sumOf { it.toDouble()*hist[it] };var weight=0;var backgroundSum=0.0;var best=-1.0;var threshold=128
        for(t in 0..254) { weight+=hist[t];if(weight==0)continue;val foreground=total-weight;if(foreground==0)break;backgroundSum+=t.toDouble()*hist[t];val diff=backgroundSum/weight-(sum-backgroundSum)/foreground;val variance=weight.toDouble()*foreground*diff*diff;if(variance>best){best=variance;threshold=t+1} }
        return threshold.coerceIn(25,235)
    }
    fun components(g: Gray, threshold: Int, dilate: Int = 0): List<Rect> {
        val mask=BooleanArray(g.pixels.size) { g.pixels[it]<threshold }
        val joined=if(dilate==0) mask else BooleanArray(mask.size).also { out ->
            for(y in 0 until g.height) for(x in 0 until g.width) if(mask[y*g.width+x]) {
                for(dy in max(0,y-dilate)..min(g.height-1,y+dilate)) for(dx in max(0,x-dilate)..min(g.width-1,x+dilate)) out[dy*g.width+dx]=true
            }
        }
        val visited=BooleanArray(mask.size);val queue=IntArray(mask.size);val result=mutableListOf<Rect>()
        for(i in joined.indices) {
            if(!joined[i] || visited[i])continue
            var head=0;var tail=0;queue[tail++]=i;visited[i]=true;var l=i%g.width;var r=l;var t=i/g.width;var b=t
            while(head<tail) { val p=queue[head++];val x=p%g.width;val y=p/g.width;l=min(l,x);r=max(r,x);t=min(t,y);b=max(b,y)
                for(next in intArrayOf(if(x>0)p-1 else -1,if(x<g.width-1)p+1 else -1,if(y>0)p-g.width else -1,if(y<g.height-1)p+g.width else -1)) if(next>=0 && joined[next] && !visited[next]) { visited[next]=true;queue[tail++]=next }
            }
            if(tail>=5)result+=Rect(l,t,r+1,b+1)
        }
        return result
    }
    /** Finds aligned dark glyph groups and rectangular display surrounds, independently of ML Kit. */
    fun displayRegions(bitmap: Bitmap): List<Rect> {
        val g=gray(bitmap,720);val parts=components(g,otsu(g),1)
        val digits=parts.filter { it.height() in 8..(g.height*0.45).toInt() && it.width() in 2..it.height() }
        val groups=digits.mapNotNull { seed ->
            val aligned=digits.filter { abs(it.centerY()-seed.centerY())<seed.height()*0.3 && it.height() in (seed.height()*0.65).toInt()..(seed.height()*1.4).toInt() && abs(it.centerX()-seed.centerX())<seed.height()*7 }
            if(aligned.size<3) null else Rect(aligned.first()).apply { aligned.drop(1).forEach(::union) }
        }
        val surrounds=parts.filter { it.width().toFloat()/it.height() in 2f..10f && it.height() in 15..(g.height*0.5).toInt() }
        return (groups+surrounds).sortedByDescending { it.width()*it.height() }.distinctBy { listOf(it.centerX()/30,it.centerY()/25,it.width()/40) }.take(4).map { r ->
            val px=max(3,r.height()/8);val sx=bitmap.width.toFloat()/g.width;val sy=bitmap.height.toFloat()/g.height
            Rect(((r.left-px)*sx).toInt().coerceAtLeast(0),((r.top-px)*sy).toInt().coerceAtLeast(0),((r.right+px)*sx).toInt().coerceAtMost(bitmap.width),((r.bottom+px)*sy).toInt().coerceAtMost(bitmap.height))
        }
    }
    fun sevenSegment(bitmap: Bitmap): String? {
        val g=gray(bitmap,900);val threshold=otsu(g);val dilate=max(1,g.height/100).coerceAtMost(3)
        val parts=components(g,threshold,dilate)
        val glyphs=parts.filter { it.height()>g.height*0.35 && it.width().toFloat()/it.height() in 0.08f..0.9f }.sortedBy { it.left }
        if(glyphs.size !in 3..10)return null
        val height=glyphs.maxOf { it.height() };if(glyphs.any { it.height()<height*0.85 })return null
        val samples=listOf(floatArrayOf(.25f,.02f,.75f,.15f),floatArrayOf(.8f,.18f,.98f,.42f),floatArrayOf(.8f,.58f,.98f,.82f),floatArrayOf(.25f,.85f,.75f,.98f),floatArrayOf(.02f,.58f,.2f,.82f),floatArrayOf(.02f,.18f,.2f,.42f),floatArrayOf(.25f,.43f,.75f,.57f))
        val decoded=StringBuilder()
        for((index,box) in glyphs.withIndex()) {
            if(box.width()<height*0.35) return null // Do not infer missing geometry of narrow or partial glyphs.
            val levels=samples.map { s ->
                var dark=0;var total=0
                for(y in (box.top+s[1]*box.height()).toInt() until (box.top+s[3]*box.height()).toInt()) for(x in (box.left+s[0]*box.width()).toInt() until (box.left+s[2]*box.width()).toInt()) { total++;if(g.pixels[y*g.width+x]<threshold)dark++ }
                if(total==0) Float.NaN else dark.toFloat()/total
            }
            decoded.append(SevenSegment.decode(levels) ?: return null)
            val next=glyphs.getOrNull(index+1)
            if(next!=null) {
                val dots=parts.filter { it.left>=box.right && it.right<=next.left && it.top>box.top+height*.7 && it.height() in 2..max(2,height/5) && it.width() in 2..max(2,height/5) }
                if(dots.size>1)return null
                if(dots.size==1)decoded.append('.')
            }
        }
        return decoded.toString().takeIf { decimal(it)!=null }
    }
}
