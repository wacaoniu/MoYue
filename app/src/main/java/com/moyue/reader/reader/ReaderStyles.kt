package com.moyue.reader.reader

import java.nio.charset.Charset

/** 阅读主题：Compose 侧颜色与 WebView 侧 CSS 值保持一致 */
data class ReaderPalette(
    val name: String,
    val bgCss: String,
    val fgCss: String,
)

object ReaderPalettes {
    val themes = listOf(
        ReaderPalette("纸张", "#FDFDFB", "#1B1D22"),
        ReaderPalette("米黄", "#F6EFDD", "#57493A"),
        ReaderPalette("护眼", "#CCE6CF", "#2C3A2E"),
        ReaderPalette("夜间", "#101014", "#B8BCC6"),
    )

    /** CSS font-family 值，注意统一使用双引号，方便注入 JS 字符串 */
    val fontStacks = listOf(
        "Georgia, \"Noto Serif CJK SC\", \"Source Han Serif SC\", serif",
        "\"Roboto\", \"Noto Sans CJK SC\", \"PingFang SC\", sans-serif",
    )
    val fontNames = listOf("衬线", "无衬线")
}

/**
 * 渲染管线：把章节 HTML 注入 CSS 变量（背景/前景/字体）、视口、
 * 以及滚动位置恢复 / 进度上报 / 翻页的 JS。
 */
object ReaderHtml {

    fun inject(
        html: String,
        anchor: String?,
        ratio: Double,
        palette: ReaderPalette,
        fontStack: String,
    ): String {
        val hasViewport = Regex("name\\s*=\\s*[\"']?viewport", RegexOption.IGNORE_CASE).containsMatchIn(html)
        val viewport = if (hasViewport) "" else "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"/>"

        val anchorLit = when {
            anchor.isNullOrEmpty() -> "null"
            else -> "'" + anchor
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("<", "\\u003c") + "'"
        }

        val css = "<style>" +
            ":root{--rbg:${palette.bgCss};--rfg:${palette.fgCss};--rfont:${fontStack};}" +
            "html{background:var(--rbg) !important;}" +
            "body{background:transparent !important;background-color:transparent !important;" +
            "color:var(--rfg) !important;font-family:var(--rfont) !important;" +
            "line-height:1.75 !important;margin:0 !important;padding:0.6em 0.9em !important;}" +
            "a{color:inherit !important;}" +
            "img,svg,video{max-width:100% !important;}" +
            "img,video{height:auto !important;}" +
            "</style>"

        val js = ("<script>(function(){" +
            "var TA=${anchorLit},TR=${"%.6f".format(ratio)};" +
            "var t=null,tries=0,stable=0,prevH=-1;" +
            "function docH(){var d=document.documentElement,b=document.body;return Math.max(d?d.scrollHeight:0,b?b.scrollHeight:0);}" +
            "function maxScroll(){var m=docH()-window.innerHeight;return m>0?m:0;}" +
            "function apply(){" +
            "if(TA){var el=document.getElementById(TA);if(!el){var nl=document.getElementsByName(TA);if(nl&&nl.length)el=nl[0];}" +
            "if(el){var y=el.getBoundingClientRect().top+(window.pageYOffset||0)-10;window.scrollTo(0,y>0?y:0);return;}}" +
            "var m=maxScroll();if(m>0)window.scrollTo(0,TR*m);}" +
            "function stop(){if(t){clearInterval(t);t=null;}}" +
            "document.addEventListener('touchstart',stop,{passive:true,once:true});" +
            // WebView 上 head 脚本先于 body 解析执行：body 为空时高度恒等于视口（m=0 恒“稳定”），
            // 不能据此停轮询——须等 body 有内容（真短章）或 m>0 后再判定稳定
            "t=setInterval(function(){var h=docH();if(h===prevH){stable++;}else{stable=0;prevH=h;}" +
            "apply();tries++;var ready=!!(document.body&&document.body.firstChild);" +
            "if((stable>=8&&(ready||maxScroll()>0))||tries>80){apply();stop();}},150);" +
            "var lastSent=-1;" +
            "function report(){var m=maxScroll();var y=window.pageYOffset||document.documentElement.scrollTop||0;" +
            "var r=m>0?(y/m):1;if(r<0)r=0;if(r>1)r=1;" +
            "if(Math.abs(r-lastSent)>0.006){lastSent=r;try{window.__AndroidReader.onScroll(r);}catch(e){}}}" +
            "window.addEventListener('scroll',report,{passive:true});" +
            // WebView 上 head 内脚本执行时 body 可能尚未布局，轮询会提前判定“稳定”而停，
            // load 时再补一次定位（覆盖恢复到章尾的场景）
            "window.addEventListener('load',function(){apply();report();});" +
            "window.addEventListener('resize',function(){apply();});" +
            "window.__rd={" +
            "page:function(d){window.scrollBy(0,Math.round(window.innerHeight*0.88*d));}," +
            "theme:function(bg,fg,f){var s=document.documentElement.style;s.setProperty('--rbg',bg);s.setProperty('--rfg',fg);s.setProperty('--rfont',f);}," +
            "anchor:function(a){TA=a;apply();}};" +
            // 章底/章顶继续滑动 → 回调原生切章（touch 手势内只触发一次，抬手后执行，800ms 节流）
            "var eLock=0,eY0=0,eB0=false,eT0=false,eFired=false,pEdge=0;" +
            "function atBot(){return maxScroll()-(window.pageYOffset||0)<4;}" +
            "function atTop(){return (window.pageYOffset||0)<4;}" +
            "function edge(d){var n=Date.now();if(n-eLock<800)return;eLock=n;" +
            "try{window.__AndroidReader.onEdge(d);}catch(e){}}" +
            "document.addEventListener('touchstart',function(e){eY0=e.touches[0].clientY;eB0=atBot();eT0=atTop();eFired=false;pEdge=0;},{passive:true});" +
            "document.addEventListener('touchmove',function(e){if(eFired){e.preventDefault();return;}" +
            "var dy=eY0-e.touches[0].clientY;var th=Math.max(48,window.innerHeight*0.05);" +
            "if(eB0&&dy>th){eFired=true;pEdge=1;e.preventDefault();}" +
            "else if(eT0&&-dy>th){eFired=true;pEdge=-1;e.preventDefault();}},{passive:false});" +
            // 抬手后才切章：手势进行中切章会让残余拖拽把新章刚恢复的滚动位置拖走
            "document.addEventListener('touchend',function(){if(pEdge){edge(pEdge);pEdge=0;}},{passive:true});" +
            "document.addEventListener('wheel',function(e){if(e.deltaY>0&&atBot())edge(1);else if(e.deltaY<0&&atTop())edge(-1);},{passive:true});" +
            "setTimeout(report,600);" +
            "})();</script>")

        val injection = viewport + css + js
        val headRe = Regex("(<head[^>]*>)", RegexOption.IGNORE_CASE)
        val htmlRe = Regex("(<html[^>]*>)", RegexOption.IGNORE_CASE)
        return when {
            headRe.containsMatchIn(html) -> html.replaceFirst(headRe, "$1" + injection)
            htmlRe.containsMatchIn(html) -> html.replaceFirst(htmlRe, "$1<head>$injection</head>")
            else -> "<html><head>$injection</head><body>$html</body></html>"
        }
    }

    /** 根据 head 里的 charset/encoding 声明解码章节文件（兼容 GBK 等老书） */
    fun decode(bytes: ByteArray): String {
        val head = String(bytes.copyOf(minOf(bytes.size, 4096)), Charsets.ISO_8859_1).lowercase()
        val m = Regex("""(charset|encoding)\s*=\s*["']?\s*([a-z0-9_\-]+)""").find(head)
        val name = m?.groupValues?.get(2)
        val charset = when {
            name == null -> Charsets.UTF_8
            name.contains("gb2312") || name.contains("gbk") || name.contains("gb18030") ->
                runCatching { Charset.forName("GB18030") }.getOrDefault(Charsets.UTF_8)
            else -> runCatching { Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
        }
        return String(bytes, charset)
    }
}
