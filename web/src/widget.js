import { normalize } from './vendor/config.js';
import { createWorld } from './vendor/three/world.js';
const style=document.createElement('style');// The home floats on the launcher's wallpaper: a transparent page, and no loading text (Luma shows the status).
style.textContent='html,body{margin:0;width:100%;height:100%;overflow:hidden;background:transparent}canvas{width:100%;height:100%;display:block}p{display:none}';document.head.append(style);
let canvas=document.querySelector('canvas');const message=document.querySelector('#message');
const listeners=new Set();const store={state:{lights:{},acs:{}},on(fn){listeners.add(fn);return()=>listeners.delete(fn);}};
let world,revision='',lastState='',active=true;
const WIDGET_ZOOM=1.12; // no card around it: leave a little air so no corner of the model is ever cut off
const resizeObserver=new ResizeObserver(()=>world?.resize());resizeObserver.observe(canvas);
function refresh(){
 try{
  const home=JSON.parse(window.HouseWidget?.home()||'{}');if(!home.plan){message.textContent=home.error||'Open House Control to set up your home.';return;}
  const serialized=JSON.stringify(home.state);
  if(home.revision!==revision){world?.dispose();world=null;resizeObserver.unobserve(canvas);const replacement=document.createElement('canvas');replacement.setAttribute('aria-label','Live 3D home');canvas.replaceWith(replacement);canvas=replacement;resizeObserver.observe(canvas);store.state=home.state;const config=normalize(home.plan);world=createWorld(canvas,config,store,{onHover(){},onPick(){},background:'transparent',presentationAspect:window.HouseWidget?.renderAspect?.()||null,resolution:()=>window.HouseWidget?.resolution?.()??1});world.setDaylight(!!window.HouseWidget?.daylight?.(),true);world.setInsets(0,0);world.setZoom(WIDGET_ZOOM);world.setRunning(false);world.settle();revision=home.revision;setTimeout(()=>window.HouseWidget?.ready?.(),0);}
  else if(serialized!==lastState){store.state=home.state;for(const fn of listeners)fn({type:'bulk'});}
  lastState=serialized;message.textContent='';
 }catch(error){message.textContent='Your home will appear here shortly.';console.warn('Widget',error.message);}
}
// Luma never shows this view: it reads pictures of the settled model (widgetSnapshot), so no frame loop runs here.
window.widgetRunning=on=>{active=on;};
window.widgetRefresh=refresh;
window.widgetTheme=day=>world?.setDaylight(day);
window.widgetCinematic=()=>world?.cinematicTV(1350);
// The card fills the screen: draw at full resolution for the flight into the modelled TV.
// A still of the settled model (final light state) for the launcher's portal; WebGL is read in the same task as the render.
window.widgetSnapshot=(settled=true)=>{if(!world)return '';if(settled)world.settle(4);else world.renderFrame(0);const p=world.tvPoint();return JSON.stringify({image:canvas.toDataURL('image/webp',.92),tv:p});};
window.widgetPortal=(ms=1350)=>{world?.cinematicTV(ms);};
window.widgetResetCamera=()=>{world?.resetPresentation();world?.focus(null);world?.setZoom(WIDGET_ZOOM);world?.settle();};
window.widgetQuality=()=>world?.stats();
refresh();setInterval(refresh,1800);
