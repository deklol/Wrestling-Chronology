const queue=[];
let running=0;

export function localThumbnail(file){
  return new Promise(resolve=>{queue.push({file,resolve});pump();});
}

function pump(){
  while(running<2&&queue.length){
    const task=queue.shift();running++;
    capture(task.file).then(task.resolve).finally(()=>{running--;pump();});
  }
}

function capture(file){
  return new Promise(resolve=>{
    const video=document.createElement('video'),url=URL.createObjectURL(file);
    let finished=false;
    const finish=value=>{
      if(finished)return;finished=true;clearTimeout(timer);
      video.onloadeddata=null;video.onerror=null;video.pause();video.removeAttribute('src');video.load();URL.revokeObjectURL(url);resolve(value);
    };
    const timer=setTimeout(()=>finish(''),4000);
    video.muted=true;video.playsInline=true;video.preload='auto';
    video.onerror=()=>finish('');
    video.onloadeddata=()=>{
      try{
        if(!video.videoWidth||!video.videoHeight)return finish('');
        const canvas=document.createElement('canvas');canvas.width=320;canvas.height=180;
        const context=canvas.getContext('2d'),scale=Math.min(320/video.videoWidth,180/video.videoHeight),width=video.videoWidth*scale,height=video.videoHeight*scale;
        context.fillStyle='#080b12';context.fillRect(0,0,320,180);context.drawImage(video,(320-width)/2,(180-height)/2,width,height);finish(canvas.toDataURL('image/webp',0.65));
      }catch{finish('');}
    };
    video.src=url;
  });
}
