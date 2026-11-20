# Temporary Play listing placeholders: mock-ups of ViewerScreen with a synthetic picture.
import html
P = {
 'rot':"M15.55,5.55L11,1v3.07C7.06,4.56 4,7.92 4,12s3.05,7.44 7,7.93v-2.02c-2.84,-0.48 -5,-2.94 -5,-5.91s2.16,-5.43 5,-5.91V10l4.55,-4.45zM19.93,11c-0.17,-1.39 -0.72,-2.73 -1.62,-3.89l-1.42,1.42c0.54,0.75 0.88,1.6 1.02,2.47h2.02zM13,17.9v2.02c1.39,-0.17 2.74,-0.71 3.9,-1.61l-1.44,-1.44c-0.75,0.54 -1.59,0.89 -2.46,1.03zM16.89,15.48l1.42,1.41c0.9,-1.16 1.45,-2.5 1.62,-3.89h-2.02c-0.14,0.87 -0.48,1.72 -1.02,2.48z",
 'flip':"M15,21h2v-2h-2V21zM19,9h2V7h-2V9zM3,5v14c0,1.1 0.9,2 2,2h4v-2H5V5h4V3H5C3.9,3 3,3.9 3,5zM19,3v2h2C21,3.9 20.1,3 19,3zM11,23h2V1h-2V23zM19,17h2v-2h-2V17zM15,5h2V3h-2V5zM19,13h2v-2h-2V13zM19,21c1.1,0 2,-0.9 2,-2h-2V21z",
 'cam':"M12,12m-3.2,0a3.2,3.2 0 1,0 6.4,0a3.2,3.2 0 1,0 -6.4,0M9,2L7.17,4H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2h-3.17L15,2H9zM12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5z",
 'rec':"M12,12m-8,0a8,8 0 1,0 16,0a8,8 0 1,0 -16,0",
 'tune':"M3,17v2h6v-2H3zM3,5v2h10V5H3zM13,21v-2h8v-2h-8v-2h-2v6h2zM7,9v2H3v2h4v2h2V9H7zM21,13v-2H11v2h10zM15,9h2V7h4V5h-4V3h-2v6z",
 'info':"M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM13,17h-2v-6h2v6zM13,9h-2L11,7h2v2z",
}
def btn(k, color='#fff', checked=False):
    bg = '#6650A4'
    if checked: bg = '#6650A4'
    else: bg = '#6650A4'
    fill = '#E53935' if k=='rec' else color
    return f'<div class="b"><svg viewBox="0 0 24 24" width="72" height="72"><path d="{P[k]}" fill="{fill}"/></svg></div>'
bar = ''.join(btn(k) for k in ['rot','flip','cam','rec','tune','info'])
pic = '''<svg class="pic" viewBox="0 0 1080 1920" preserveAspectRatio="xMidYMid slice">
<defs><radialGradient id="p" cx="50%" cy="46%" r="62%"><stop offset="0" stop-color="#f4e9cf"/><stop offset=".12" stop-color="#cdb98f"/><stop offset=".38" stop-color="#7c6445"/><stop offset=".7" stop-color="#2d2318"/><stop offset="1" stop-color="#0b0906"/></radialGradient></defs>
<rect width="1080" height="1920" fill="url(#p)"/>
<g fill="none" stroke="#000" stroke-opacity=".28" stroke-width="10">''' + ''.join(f'<circle cx="540" cy="880" r="{r}"/>' for r in (80,160,260,380,520,700)) + '''</g>
<circle cx="540" cy="880" r="40" fill="#fff" fill-opacity=".55"/></svg>'''
def screen(name, body, extra=''):
    return f'''<!doctype html><meta charset=utf-8><style>
*{{box-sizing:border-box;margin:0}}body{{width:1080px;height:1920px;background:#000;position:relative;overflow:hidden;font-family:Inter,sans-serif;color:#fff}}
.pic{{position:absolute;inset:0;width:100%;height:100%}}
.sb{{z-index:5;position:absolute;top:0;left:0;right:0;height:72px;padding:0 36px;display:flex;justify-content:space-between;align-items:center;font-size:30px;font-weight:500}}
.fmt{{position:absolute;top:84px;left:36px;font-size:33px;font-weight:500}}
.bar{{position:absolute;left:0;right:0;bottom:96px;padding:0 72px;display:flex;justify-content:space-between}}
.b{{width:120px;height:120px;border-radius:60px;background:#6650A4;display:flex;align-items:center;justify-content:center}}
.panel{{position:absolute;top:180px;left:36px;right:36px;background:rgba(0,0,0,.8);border-radius:36px;padding:36px}}
.row{{margin:30px 0}} .lab{{display:flex;justify-content:space-between;font-size:33px}}
.trk{{height:12px;background:#49454F;border-radius:6px;margin-top:24px;position:relative}}
.trk i{{position:absolute;left:0;top:0;bottom:0;background:#D0BCFF;border-radius:6px}}
.trk u{{position:absolute;top:-18px;width:48px;height:48px;border-radius:24px;background:#D0BCFF;margin-left:-24px}}
.mock{{position:absolute;bottom:20px;left:0;right:0;text-align:center;font-size:24px;letter-spacing:2px;color:#fff;opacity:.55}}
.msg{{position:absolute;top:0;bottom:0;left:0;right:0;display:flex;align-items:center;justify-content:center;font-size:42px;padding:96px;text-align:center}}
</style>
<div class=sb><span>9:41</span><span>●●● ▮</span></div>{body}
<div class=bar>{bar}</div><div class=mock>TEMPORARY MOCKUP – REPLACE WITH REAL SCREENSHOT</div>'''
def sliders():
    rows=[('Brightness',62),('Contrast',48),('Saturation',55),('Sharpness',35),('Gain',20)]
    return '<div class=panel>'+''.join(f'<div class=row><div class=lab><span>{n}</span><span>{v}</span></div><div class=trk><i style="width:{v}%"></i><u style="left:{v}%"></u></div></div>' for n,v in rows)+'</div>'
S = {
 '1-live': pic+'<div class=fmt>MJPEG 1280×720 @ 30 fps</div>',
 '2-controls': pic+'<div class=fmt>MJPEG 1280×720 @ 30 fps</div>'+sliders(),
 '3-no-camera': '<div class=msg>Plug in a USB borescope</div>',
}
for k,v in S.items():
    open(f'{k}.html','w').write(screen(k,v))
