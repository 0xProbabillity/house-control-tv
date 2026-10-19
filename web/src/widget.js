import { normalize } from './vendor/config.js';
import { createWorld } from './vendor/three/world.js';
const style=document.createElement('style');style.textContent='html,body{margin:0;width:100%;height:100%;overflow:hidden;background:#0b111d;color:#9baecb;font-family:sans-serif}canvas{width:100%;height:100%;display:block}p{position:absolute;inset:0;display:grid;place-items:center;font-size:12px;padding:1rem;text-align:center;pointer-events:none}';document.head.append(style);
let canvas=document.querySelector('canvas');const message=document.querySelector('#message');
const listeners=new Set();const store={state:{lights:{},acs:{}},on(fn){listeners.add(fn);return()=>listeners.delete(fn);}};
let world,revision='',lastState='',active=true;
const WIDGET_ZOOM=0.9; // the card is small: frame the model a little tighter than the full app
const resizeObserver=new ResizeObserver(()=>world?.resize());resizeObserver.observe(canvas);
function refresh(){
 try{
  const home=JSON.parse(window.HouseWidget?.home()||'{}');if(!home.plan){message.textContent=home.error||'Open House Control to set up your home.';return;}
  const serialized=JSON.stringify(home.state);
  if(home.revision!==revision){world?.dispose();world=null;resizeObserver.unobserve(canvas);const replacement=document.createElement('canvas');replacement.setAttribute('aria-label','Live 3D home');canvas.replaceWith(replacement);canvas=replacement;resizeObserver.observe(canvas);store.state=home.state;const config=normalize(home.plan);world=createWorld(canvas,config,store,{onHover(){},onPick(){},background:'#0b111d',presentationAspect:window.HouseWidget?.renderAspect?.()||null,resolution:()=>window.HouseWidget?.resolution?.()??1});world.setInsets(0,0);world.setZoom(WIDGET_ZOOM);world.setRunning(active&&!document.hidden);world.settle();revision=home.revision;}
  else if(serialized!==lastState){store.state=home.state;for(const fn of listeners)fn({type:'bulk'});world?.setRunning(active&&!document.hidden);}
  lastState=serialized;message.textContent='';
 }catch(error){message.textContent='Your home will appear here shortly.';console.warn('Widget',error.message);}
}
document.addEventListener('visibilitychange',()=>world?.setRunning(active&&!document.hidden));
window.widgetRunning=on=>{active=on;world?.setRunning(active&&!document.hidden);};
window.widgetRefresh=refresh;
window.widgetCinematic=()=>world?.cinematicTV(1350);
// The card fills the screen: draw at full resolution for the flight into the modelled TV.
// A still of the settled model (final light state) for the launcher's portal; WebGL is read in the same task as the render.
window.widgetSnapshot=(settled=true)=>{if(!world)return '';if(settled)world.settle(4);else world.renderFrame(0);const p=world.tvPoint();return JSON.stringify({image:canvas.toDataURL('image/jpeg',.9),tv:p});};
window.widgetPortal=(ms=1350)=>{world?.cinematicTV(ms);};
window.widgetResetCamera=()=>{world?.resetPresentation();world?.focus(null);world?.setZoom(WIDGET_ZOOM);world?.settle();};
window.widgetQuality=()=>world?.stats();
refresh();setInterval(refresh,1800);
