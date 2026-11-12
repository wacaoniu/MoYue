package com.moyue.reader.reader

import com.moyue.reader.data.SpineItem
import java.io.File

/**
 * 漫画（webtoon 长条）模式支持：
 * - 识别一本书是否是漫画/图多书（spine 大部分是纯图片页）
 * - 从 spine 章节中提取图片（img@src 与 svg image@href，含 xlink）
 * - 生成整章图片零间距竖排、可双指缩放、滚动到头自动衔接上下章的长条页面
 */
object ComicSupport {

    /** 判断一本书是否应当默认用漫画长条模式阅读 */
    fun detectComicMode(bookDir: File, spine: List<SpineItem>): Boolean {
        if (spine.size < 2) return false
        // 章节太多时均匀抽样 20 个，避免导入大书时全量读文件
        val sample = if (spine.size <= 24) spine else {
            val step = spine.size / 20.0
            (0 until 20).map { spine[(it * step).toInt().coerceAtMost(spine.lastIndex)] }
        }
        var imagePages = 0
        var counted = 0
        for (item in sample) {
            val f = File(bookDir, item.href)
            if (!f.exists()) continue
            if (item.mediaType.startsWith("image/")) {
                imagePages++; counted++; continue
            }
            if (!item.mediaType.contains("html", ignoreCase = true)) continue
            counted++
            if (isMostlyImages(f)) imagePages++
        }
        return counted >= 2 && imagePages.toDouble() / counted >= 0.6
    }

    /** 页面基本只有图（文字少于 80 个非空白字符）就算图片页 */
    fun isMostlyImages(xhtmlFile: File): Boolean {
        val html = runCatching { ReaderHtml.decode(xhtmlFile.readBytes()) }.getOrNull() ?: return false
        val hasImage = html.contains("<img") ||
            Regex("<image[^>]*?href", RegexOption.IGNORE_CASE).containsMatchIn(html)
        if (!hasImage) return false
        val textLen = Regex("<[^>]*>").replace(html, "").filter { !it.isWhitespace() }.length
        return textLen <= 80
    }

    /** 提取一个 spine 项的图片（书内相对路径），按出现顺序；无图返回空 */
    fun chapterImages(bookDir: File, item: SpineItem): List<String> {
        if (item.mediaType.startsWith("image/")) {
            return if (File(bookDir, item.href).isFile) listOf(item.href) else emptyList()
        }
        val f = File(bookDir, item.href)
        if (!f.isFile) return emptyList()
        val html = runCatching { ReaderHtml.decode(f.readBytes()) }.getOrNull() ?: return emptyList()
        val baseDir = item.href.substringBeforeLast('/', "")
        val found = mutableListOf<Pair<Int, String>>()
        for (m in Regex("<img[^>]*?\\ssrc\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).findAll(html)) {
            found.add(m.range.first to m.groupValues[1])
        }
        for (m in Regex("<image[^>]*?(?:xlink:)?href\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).findAll(html)) {
            found.add(m.range.first to m.groupValues[1])
        }
        return found.sortedBy { it.first }
            .map { (_, src) -> com.moyue.reader.data.EpubParser.resolvePath(baseDir, src) }
            .filter { File(bookDir, it).isFile }
    }

    /** 漫画书里偶尔夹带的纯文字页：取 body 内容 */
    fun chapterBodyText(xhtmlFile: File): String? {
        val html = runCatching { ReaderHtml.decode(xhtmlFile.readBytes()) }.getOrNull() ?: return null
        val body = Regex("<body[^>]*>([\\s\\S]*?)</body>", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1) ?: return null
        return body.takeIf { it.trim().isNotEmpty() }
    }

    /**
     * 只读文件头部解析图片宽高（JPEG/PNG/GIF/WebP）。
     * 用途：给 <img> 预设 aspect-ratio，让每张图在加载前就占好位置——
     * 消除懒加载图片出图时的整条重排（滑动卡顿的主要来源），滚动条/章界也不会跳。
     */
    object ImageSize {
        fun read(file: File): Pair<Int, Int>? {
            if (file.length() < 16) return null
            val head = try {
                val buf = ByteArray(131072) // JPEG 的 SOF 通常在头部几 KB 内，128KB 足够
                file.inputStream().use { ins ->
                    var off = 0
                    while (off < buf.size) {
                        val r = ins.read(buf, off, buf.size - off)
                        if (r <= 0) break
                        off += r
                    }
                    buf.copyOf(off)
                }
            } catch (_: Exception) {
                return null
            }
            if (head.size < 16) return null
            return jpeg(head) ?: png(head) ?: gif(head) ?: webp(head)
        }

        private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
        private fun u16be(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
        private fun u16le(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)

        private fun jpeg(b: ByteArray): Pair<Int, Int>? {
            if (u8(b, 0) != 0xFF || u8(b, 1) != 0xD8) return null
            var i = 2
            while (i + 9 < b.size) {
                if (u8(b, i) != 0xFF) { i++; continue }
                val marker = u8(b, i + 1)
                if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) { i += 2; continue }
                if (marker == 0xD9 || marker == 0xDA) return null // 到扫描数据也没遇到 SOF
                if (i + 4 > b.size) break
                val segLen = u16be(b, i + 2)
                if (segLen < 2) break
                val isSof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
                if (isSof) {
                    if (i + 9 <= b.size) {
                        val h = u16be(b, i + 5)
                        val w = u16be(b, i + 7)
                        if (w > 0 && h > 0) return w to h
                    }
                    return null
                }
                i += 2 + segLen
            }
            return null
        }

        private fun png(b: ByteArray): Pair<Int, Int>? {
            if (u8(b, 0) != 0x89 || u8(b, 1) != 'P'.code) return null
            if (b.size < 24) return null
            val w = u16be(b, 16) shl 16 or u16be(b, 18)
            val h = u16be(b, 20) shl 16 or u16be(b, 22)
            return if (w > 0 && h > 0) w to h else null
        }

        private fun gif(b: ByteArray): Pair<Int, Int>? {
            if (u8(b, 0) != 'G'.code || u8(b, 1) != 'I'.code) return null
            val w = u16le(b, 6)
            val h = u16le(b, 8)
            return if (w > 0 && h > 0) w to h else null
        }

        private fun webp(b: ByteArray): Pair<Int, Int>? {
            if (u8(b, 0) != 'R'.code || u8(b, 8) != 'W'.code) return null
            when {
                u8(b, 12) == 'V'.code && u8(b, 13) == 'P'.code && u8(b, 14) == '8'.code && u8(b, 15) == 'X'.code -> {
                    if (b.size < 30) return null
                    val w = 1 + (u8(b, 24) or (u8(b, 25) shl 8) or (u8(b, 26) shl 16))
                    val h = 1 + (u8(b, 27) or (u8(b, 28) shl 8) or (u8(b, 29) shl 16))
                    return if (w > 1 && h > 1) w to h else null
                }
                u8(b, 12) == 'V'.code && u8(b, 13) == 'P'.code && u8(b, 14) == '8'.code && u8(b, 15) == ' '.code -> {
                    if (b.size < 30) return null
                    // VP8 lossy: 同步码 0x9D012A 后跟宽高（LE14 各 2 字节）
                    if (u8(b, 23) != 0x9D || u8(b, 24) != 0x01 || u8(b, 25) != 0x2A) return null
                    val w = u16le(b, 26) and 0x3FFF
                    val h = u16le(b, 28) and 0x3FFF
                    return if (w > 0 && h > 0) w to h else null
                }
                u8(b, 12) == 'V'.code && u8(b, 13) == 'P'.code && u8(b, 14) == '8'.code && u8(b, 15) == 'L'.code -> {
                    if (b.size < 25 || u8(b, 20) != 0x2F) return null
                    val bits = u8(b, 21) or (u8(b, 22) shl 8) or (u8(b, 23) shl 16) or (u8(b, 24) shl 24)
                    val w = (bits and 0x3FFF) + 1
                    val h = ((bits ushr 14) and 0x3FFF) + 1
                    return if (w > 1 && h > 1) w to h else null
                }
            }
            return null
        }
    }

    /**
     * 长条页面外壳。特点：
     * - 图片 width:100%、display:block、margin/padding 全 0 —— 图片之间零留白，连续滚动
     * - WebView 原生双指缩放（不锁缩放），不用点开图片
     * - 滚动接近底部/顶部时通过 JS 桥请求 Kotlin 追加下一章/回填上一章，实现无缝连续阅读
     * - restoreRatio 恢复章内位置；targetPage >= 0 时恢复到精确页码（pdf/cbz 页级导航）
     */
    fun shellHtml(palette: ReaderPalette, restoreRatio: Double, targetPage: Int = -1, seam: Int = 1): String {
        val seamCss = when (seam) {
            0 -> "gap:0;"
            2 -> "gap:10px;"
            else -> "gap:1px;"
        }
        return """
<!DOCTYPE html><html><head>
<meta name="viewport" content="width=device-width, initial-scale=1.0"/>
<style>
:root{--rbg:${palette.bgCss};--rfg:${palette.fgCss};}
html,body{margin:0;padding:0;background:var(--rbg) !important;}
#strip{width:100%;}
#strip img{display:block;width:100%;height:auto;margin:0;padding:0;border:0;}
#strip .pair{display:flex;width:100%;$seamCss}
#strip .pair img{width:50%;}
#strip .split-half{width:100%;overflow:hidden;}
#strip .split-half img{width:200% !important;max-width:none !important;}
#strip .split-half.second img{margin-left:-100%;}
#strip .txtpage{display:block;padding:14px 16px;color:var(--rfg);font-size:15px;line-height:1.7;}
#strip .txtpage p{margin:0.5em 0;}
#strip .tail{display:block;padding:26px 0;text-align:center;color:var(--rfg);font-size:13px;opacity:0.55;}
</style></head>
<body><div id="strip"><!--INIT--></div>
<script>
(function(){
var A=window.__AndroidComic;
var TR=${"%.6f".format(restoreRatio)};
var TP=$targetPage;
var strip=document.getElementById('strip');
var t=null,tries=0,stable=0,prevH=-1;
function stop(){if(t){clearInterval(t);t=null;}}
document.addEventListener('touchstart',stop,{passive:true,once:true});
function gotoPage(n){
  var el=document.querySelector('[data-pg="'+n+'"]');
  if(!el){var els=strip.querySelectorAll('[data-pg]');if(els.length){el=els[els.length-1];}}
  if(el){var y=el.getBoundingClientRect().top+(window.pageYOffset||0)-2;window.scrollTo(0,y>0?y:0);}
}
t=setInterval(function(){
  var h=strip.offsetHeight;
  try{A.onMetric(document.documentElement.scrollHeight,window.innerHeight,strip.children.length,strip.querySelectorAll('.pair').length);}catch(e){}
  if(h===prevH){stable++;}else{stable=0;prevH=h;}
  if(TP>=0){
    gotoPage(TP);
  } else if(TR>0&&TR<1&&strip.children.length>0){
    var m=strip.children[0].offsetHeight-window.innerHeight;
    if(m>0)window.scrollTo(0,TR*m);
  }
  tries++;
  if(stable>=5||tries>80){
    if(TP>=0){gotoPage(TP);}
    else if(TR>0&&TR<1&&strip.children.length>0){
      var m2=strip.children[0].offsetHeight-window.innerHeight;
      if(m2>0)window.scrollTo(0,TR*m2);
    }
    stop();
  }
},150);
window.__comic={
  append:function(index,inner){
    var d=document.createElement('div');
    d.setAttribute('data-ch',index);
    d.innerHTML=inner;
    strip.appendChild(d);
    // 追加后立刻重新报告：如果当前仍不足一屏，原生端会继续接下一章，
    // 不要求用户先滑动才能触发下一次加载。
    setTimeout(report,0);
    setTimeout(report,220);
  },
  prepend:function(index,inner){
    var d=document.createElement('div');
    d.setAttribute('data-ch',index);
    d.innerHTML=inner;
    var h1=strip.offsetHeight;
    strip.insertBefore(d,strip.firstChild);
    window.scrollBy(0,strip.offsetHeight-h1);
    setTimeout(report,0);
    setTimeout(report,220);
  },
  page:function(dir){window.scrollBy(0,Math.round(window.innerHeight*0.88*dir));},
  theme:function(bg,fg){var s=document.documentElement.style;s.setProperty('--rbg',bg);s.setProperty('--rfg',fg);},
  resetZoom:function(){var m=document.querySelector('meta[name=viewport]');if(m){m.setAttribute('content','width=device-width, initial-scale=1.0');}},
  gotoPage:gotoPage
};
function curChapter(){
  var divs=strip.children;var ch=null;var probe=window.innerHeight*0.35;
  for(var i=0;i<divs.length;i++){
    var a=divs[i].getAttribute('data-ch');
    if(a!==null&&divs[i].getBoundingClientRect().top<=probe){ch=+a;}
  }
  return ch;
}
function curPage(){
  var els=strip.querySelectorAll('[data-pg]');
  var p=null;var probe=window.innerHeight*0.35;
  for(var i=0;i<els.length;i++){
    if(els[i].getBoundingClientRect().top<=probe){p=+els[i].getAttribute('data-pg');}
  }
  return p;
}
function ratioOf(ch){
  var divs=strip.children;
  for(var i=0;i<divs.length;i++){
    var a=divs[i].getAttribute('data-ch');
    if(a!==null&&+a===ch){
      var top=divs[i].getBoundingClientRect().top+(window.pageYOffset||0);
      var m=divs[i].offsetHeight-window.innerHeight;
      if(m<=0)return 1;
      var r=((window.pageYOffset||0)-top)/m;
      return r<0?0:(r>1?1:r);
    }
  }
  return 0;
}
var lastCh=null,lastR=-1,lastPg=null,ra=0;
// 加载失败的图直接隐藏，保持长条连续（坏书/缺图场景）
document.addEventListener('error',function(ev){
  var t=ev.target;
  if(t&&t.tagName==='IMG'){t.style.display='none';setTimeout(report,0);}
},true);
function report(){
  var ch=curChapter();
  if(ch===null||ch<0)return;
  var r=ratioOf(ch);
  if(ch!==lastCh||Math.abs(r-lastR)>0.02){
    lastCh=ch;lastR=r;
    try{A.onProgress(ch,r);}catch(e){}
  }
  var pg=curPage();
  if(pg!==null&&pg!==lastPg){
    lastPg=pg;
    try{A.onPage(pg);}catch(e){}
  }
  var bottom=Math.max(0,(document.documentElement.scrollHeight||0)-window.innerHeight-(window.pageYOffset||0));
  if(bottom<window.innerHeight*3.0){try{A.onNearEnd();}catch(e){}}
  if((window.pageYOffset||0)<=4){try{A.onReachTop();}catch(e){}}
}
function scheduleReport(){if(ra)return;ra=setTimeout(function(){ra=0;report();},80);}
window.addEventListener('scroll',scheduleReport,{passive:true});
window.addEventListener('resize',scheduleReport);
document.addEventListener('load',function(ev){if(ev.target&&ev.target.tagName==='IMG')scheduleReport();},true);
if(window.ResizeObserver){new ResizeObserver(scheduleReport).observe(strip);}
window.addEventListener('load',report);
setTimeout(report,400);
})();
</script></body></html>
""".trimIndent()
    }
}
