import json, subprocess, sys, os
D=os.path.dirname(os.path.abspath(__file__))
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
TPL='''<!doctype html><html><head><meta charset="utf-8">
<link rel="stylesheet" href="https://cdn.jsdelivr.net/gh/orioncactus/pretendard@v1.3.9/dist/web/static/pretendard.min.css">
<style>
*{margin:0;padding:0;box-sizing:border-box}
html,body{width:1080px;height:1920px;overflow:hidden}
body{font-family:Pretendard,"Apple SD Gothic Neo",sans-serif;background:linear-gradient(160deg,#34476B 0%,#223049 55%,#151E30 100%);position:relative;color:#fff}
.c1{position:absolute;width:720px;height:720px;border-radius:50%;background:rgba(255,255,255,.06);right:-260px;top:40px}
.c2{position:absolute;width:640px;height:640px;border-radius:50%;background:rgba(255,255,255,.05);left:-300px;top:1180px}
.c3{position:absolute;width:520px;height:520px;border-radius:50%;background:rgba(230,190,120,.06);right:-200px;top:1500px}
.pill{position:absolute;top:70px;left:50%;transform:translateX(-50%);background:#fff;border-radius:60px;padding:16px 36px 16px 18px;display:flex;align-items:center;gap:16px;box-shadow:0 8px 24px rgba(0,0,20,.25)}
.pill img{width:58px;height:58px;border-radius:15px}
.pill span{color:#1E2A40;font-size:40px;font-weight:800;letter-spacing:-1px}
h1{position:absolute;top:205px;width:100%;text-align:center;font-size:__HS__px;font-weight:800;letter-spacing:-3px;line-height:1.15}
h1 em{font-style:normal;color:#F2C879}
p{position:absolute;top:__PT__px;width:100%;text-align:center;font-size:42px;font-weight:500;opacity:.9;letter-spacing:-1px}
.phone{position:absolute;left:160px;top:__FT__px;width:760px;height:1700px;background:#fff;border-radius:96px;padding:22px;box-shadow:0 30px 60px rgba(0,0,20,.45)}
.scr{width:716px;height:1656px;border-radius:76px;overflow:hidden;position:relative;background:#000}
.scr img{width:716px;position:absolute;left:0;top:__OFF__px}
</style></head><body>
<div class="c1"></div><div class="c2"></div><div class="c3"></div>
<div class="pill"><img src="icon.png"><span>__APP__</span></div>
<h1>__H__</h1><p>__P__</p>
<div class="phone"><div class="scr"><img src="__IMG__"></div></div>
</body></html>'''
def make(n, img, head, sub, off=0, hs=92, app="ShieldTap", prefix="screenshot"):
    lines=head.count("<br>")+1
    pt=205+int(hs*1.15*lines)+22
    ft=pt+90
    h=(TPL.replace("__H__",head).replace("__P__",sub).replace("__IMG__",img).replace("__OFF__",str(-off))
         .replace("__HS__",str(hs)).replace("__PT__",str(pt)).replace("__FT__",str(ft)).replace("__APP__",app))
    f=os.path.join(D,f"{prefix}_{n}.html"); open(f,"w").write(h)
    out=os.path.join(D,f"{prefix}_{n}.png")
    subprocess.run([CHROME,"--headless=new","--disable-gpu","--hide-scrollbars","--force-device-scale-factor=1","--window-size=1080,1920","--virtual-time-budget=4000",f"--screenshot={out}","file://"+f],capture_output=True)
    print(out, os.path.exists(out))
if __name__=="__main__":
    for s in json.load(open(sys.argv[1])): make(**s)
