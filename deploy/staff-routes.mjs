const STAFF_API_ORIGIN='https://polaris-polaris-hotel.up.railway.app';
export const STAFF_SECURITY={'Cache-Control':'private, no-store','X-Content-Type-Options':'nosniff','Referrer-Policy':'no-referrer','X-Frame-Options':'DENY','X-Robots-Tag':'noindex, nofollow','Content-Security-Policy':"default-src 'self'; script-src 'self' 'unsafe-inline' https://challenges.cloudflare.com; style-src 'self' 'unsafe-inline'; img-src 'self' https: data:; connect-src 'self' https://challenges.cloudflare.com; frame-src https://challenges.cloudflare.com; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'"};
export function staffError(status,error){return new Response(JSON.stringify({error}),{status,headers:{...STAFF_SECURITY,'Content-Type':'application/json; charset=utf-8'}});}
export async function staffBody(request,max){
 if(Number(request.headers.get('content-length'))>max)throw Error('too-large');
 if(!request.body)return new Uint8Array();
 const reader=request.body.getReader(),chunks=[];let n=0;
 try{for(;;){const{done,value}=await reader.read();if(done)break;n+=value.byteLength;if(n>max){await reader.cancel();throw Error('too-large');}chunks.push(value);}}finally{reader.releaseLock();}
 const body=new Uint8Array(n);let offset=0;for(const chunk of chunks){body.set(chunk,offset);offset+=chunk.byteLength;}return body;
}
export async function staffRoute(request,page,fetcher=fetch){
 const url=new URL(request.url),path=url.pathname;
 if(path==='/staff/'||path==='/community/staff')return new Response(null,{status:308,headers:{...STAFF_SECURITY,Location:'/staff'}});
 if(path==='/staff'){
  if(!['GET','HEAD'].includes(request.method))return staffError(405,'Use GET or HEAD.');
  return new Response(request.method==='HEAD'?null:page,{headers:{...STAFF_SECURITY,'Content-Type':'text/html; charset=utf-8'}});
 }
 const login=path==='/api/auth/login',news=path==='/api/community/staff/news';
 if(!login&&!news)return null;
 if((login&&request.method!=='POST')||(news&&!['GET','POST'].includes(request.method)))return staffError(405,'Method not allowed.');
 // Validate the real browser origin before translating it to the fixed upstream.
 // Polaris retains every token, online Housekeeping permission and lockdown gate.
 if(request.method==='POST'&&request.headers.get('origin')!==url.origin)return staffError(403,'Open the staff area on this website before signing in or saving.');
 if(request.method==='POST'&&!/^application\/json(?:\s*;|$)/i.test(request.headers.get('content-type')||''))return staffError(415,'Use JSON.');
 const auth=request.headers.get('authorization')||'';
 if(news&&!/^Bearer \S+$/.test(auth))return staffError(403,'Sign in with an authorized staff account.');
 let body;if(request.method==='POST'){try{body=await staffBody(request,login?8192:50000);}catch{return staffError(413,'Request is too large.');}}
 const headers=new Headers({Accept:'application/json'});if(news)headers.set('Authorization',auth);
 if(request.method==='POST'){headers.set('Content-Type','application/json');headers.set('Origin',STAFF_API_ORIGIN);}
 try{
  const response=await fetcher(STAFF_API_ORIGIN+path,{method:request.method,headers,body,redirect:'manual',signal:AbortSignal.timeout(15000)});
  if(response.status>=300&&response.status<400)return staffError(502,'Unexpected staff service redirect.');
  // Deliberately omit upstream cookies, CORS and cache headers.
  return new Response(response.body,{status:response.status,headers:{...STAFF_SECURITY,'Content-Type':response.headers.get('Content-Type')||'application/json; charset=utf-8'}});
 }catch{return staffError(502,'The staff service is temporarily unavailable. Please try again.');}
}
