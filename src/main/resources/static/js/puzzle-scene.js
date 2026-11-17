//  puzzle-scene.js 역할: HERO 전체 배경의 WebGL 3D 퍼즐 생성, 조명, 조립 단계, 드래그 회전을 담당합니다

(function () {
    'use strict';

    function mountPuzzleScene(canvas) {
    if (!canvas || canvas.dataset.puzzleSceneMounted === 'true') return;
    canvas.dataset.puzzleSceneMounted = 'true';
    //  01. WebGL 대상 canvas와 렌더링 컨텍스트 준비: 미지원 시 canvas를 숨기고 종료
    var gl = canvas.getContext('webgl', { alpha:true, antialias:true, premultipliedAlpha:false, stencil:true });
    if (!gl) { canvas.hidden = true; return; }
    var analysisMode=canvas.hasAttribute('data-analysis-puzzle');
    var PIECE_W=1.22, PIECE_H=.92, DEPTH=.16, KNOB=.2, BEVEL=.022, SAMPLES=5;
    var resultMode=canvas.hasAttribute('data-result-puzzle');

    //  02. 퍼즐 클릭 단계별 UI 데이터: 하단 단계 문구와 우측 홀로그램 화면을 함께 변경
    var stages = [
        { label:'자료 확정', title:'등록 자료 확정', pill:'추출 확인', note:'AI 추출 결과를 직접 확인하고 수정한 확정본만 분석에 사용합니다.' },
        { label:'공고·지원자 분석', title:'공고·경험 분석', pill:'분석 완료', note:'공고·회사정보와 지원자 경험을 각각 분석해 면접 근거를 정리합니다.' },
        { label:'연결도·적합도 진단', title:'요구사항 연결 진단', pill:'적합도 산출', note:'요구사항별 연결도와 부족한 근거를 진단하고 종합 적합도를 보여줍니다.' },
        { label:'질문·과제 생성', title:'맞춤 질문·과제', pill:'사전 생성', note:'연결도에 따라 맞춤 질문과 보완 과제를 면접 전에 미리 생성합니다.' },
        { label:'면접·답변 평가', title:'답변 관점 평가', pill:'8개 관점', note:'답변을 8개 고정 관점으로 평가하고 반복되는 약점 태그를 연결합니다.' },
        { label:'약점 보완·리포트', title:'다음 연습 리포트', pill:'준비 완료', note:'약점 보완 방향과 자료 수정 제안, 다음 연습 과정을 하나의 리포트로 제공합니다.' }
    ];

    //  03. 퍼즐 단계와 연동할 HTML 요소 참조
    var label = document.getElementById('puzzle-stage-label');
    var bar = document.getElementById('puzzle-stage-progress');
    var hologram = document.querySelector('.hero-hologram');
    var hologramPanels = hologram ? Array.prototype.slice.call(hologram.querySelectorAll('.hologram-panel')) : [];
    var hologramDots = hologram ? Array.prototype.slice.call(hologram.querySelectorAll('.hologram-step-dot')) : [];
    var hologramNumber = document.getElementById('hologram-stage-number');

    //  04. GLSL 셰이더 소스: vertex는 위치/법선 변환, fragment는 ambient·key/fill light·specular·rim light 계산
    var vertex = 'attribute vec3 p;attribute vec3 n;uniform mat4 m;uniform mat4 pv;varying vec3 N;varying vec3 W;varying vec3 L;varying float F;void main(){vec4 w=m*vec4(p,1.);W=w.xyz;L=p;F=n.z;N=normalize(mat3(m)*n);gl_Position=pv*w;}';
    //  엠블럼(선택): 완성 퍼즐 범위(bounds)에 대응하는 알파 마스크를 앞면에만 흰색 금박+발광으로 입힘. embOn=0이면 기존 셰이딩 그대로
    var fragment = 'precision mediump float;uniform vec3 c;uniform vec2 off;uniform vec4 bounds;uniform sampler2D emb,embB;uniform float embOn,backOn,bright,flip;uniform vec2 embTexel;varying vec3 N;varying vec3 W;varying vec3 L;varying float F;void main(){vec3 nn=normalize(N)*flip;vec2 g=(L.xy+off-bounds.xy)/bounds.zw;vec2 uf=vec2(g.x,1.-g.y),ub=vec2(1.-g.x,1.-g.y),dx=vec2(embTexel.x,0.),dy=vec2(0.,embTexel.y);float front=embOn*step(.9,F),back=backOn*step(.9,-F);float mk=front*smoothstep(.35,.65,texture2D(emb,uf).a)+back*smoothstep(.35,.65,texture2D(embB,ub).a);float ex=front*(texture2D(emb,uf+dx).a-texture2D(emb,uf-dx).a)-back*(texture2D(embB,ub+dx).a-texture2D(embB,ub-dx).a);float ey=front*(texture2D(emb,uf-dy).a-texture2D(emb,uf+dy).a)+back*(texture2D(embB,ub-dy).a-texture2D(embB,ub+dy).a);nn=normalize(nn+vec3(-ex,-ey,0.)*1.1);vec3 col=mix(c,vec3(1.),mk);vec3 key=normalize(vec3(-.35,.82,.68));vec3 fill=normalize(vec3(.48,.30,-.72));vec3 v=normalize(vec3(0.,0.,7.)-W);vec3 h=normalize(key+v);float diff=max(dot(nn,key),0.);float fillDiff=max(dot(nn,fill),0.);float spec=pow(max(dot(nn,h),0.),34.);float rim=pow(1.-max(dot(nn,v),0.),2.1);vec3 base=col*(.48+diff*.42+fillDiff*.18);vec3 sheen=vec3(.72,.9,1.)*(spec*.34+rim*.16);vec3 o=min((base+sheen)*bright,vec3(1.));o=mix(o,min(vec3(1.)+pow(max(dot(nn,h),0.),40.)*.3,vec3(1.)),mk*.7);gl_FragColor=vec4(o,1.);}';
    //  개별 셰이더를 생성하고 소스 컴파일
    function shader(type, source) { var s=gl.createShader(type); gl.shaderSource(s,source); gl.compileShader(s); return s; }
    //  vertex/fragment 셰이더를 WebGL program에 연결하고 사용 상태로 전환
    var program=gl.createProgram();
    gl.attachShader(program,shader(gl.VERTEX_SHADER,vertex));
    gl.attachShader(program,shader(gl.FRAGMENT_SHADER,fragment));
    gl.linkProgram(program);
    var lineProg=gl.createProgram();
    gl.attachShader(lineProg,shader(gl.VERTEX_SHADER,'attribute vec3 p;uniform mat4 m;uniform mat4 pv;void main(){gl_Position=pv*m*vec4(p,1.);}'));
    gl.attachShader(lineProg,shader(gl.FRAGMENT_SHADER,'precision mediump float;uniform vec4 col;uniform vec2 res;void main(){vec2 d=(gl_FragCoord.xy/res-vec2(.5,.6))/vec2(.707,.849);gl_FragColor=vec4(col.rgb,col.a*(1.-smoothstep(.3,.75,length(d))));}'));
    gl.linkProgram(lineProg);
    var lineLoc={p:gl.getAttribLocation(lineProg,'p'),m:gl.getUniformLocation(lineProg,'m'),pv:gl.getUniformLocation(lineProg,'pv'),col:gl.getUniformLocation(lineProg,'col'),res:gl.getUniformLocation(lineProg,'res')};
    gl.useProgram(program);
    //  셰이더 attribute/uniform 위치를 미리 조회
    var loc={p:gl.getAttribLocation(program,'p'),n:gl.getAttribLocation(program,'n'),m:gl.getUniformLocation(program,'m'),pv:gl.getUniformLocation(program,'pv'),c:gl.getUniformLocation(program,'c'),off:gl.getUniformLocation(program,'off'),bounds:gl.getUniformLocation(program,'bounds'),emb:gl.getUniformLocation(program,'emb'),embOn:gl.getUniformLocation(program,'embOn'),embB:gl.getUniformLocation(program,'embB'),backOn:gl.getUniformLocation(program,'backOn'),flip:gl.getUniformLocation(program,'flip'),bright:gl.getUniformLocation(program,'bright'),embTexel:gl.getUniformLocation(program,'embTexel')};
    gl.uniform1f(loc.embOn,0);gl.uniform1f(loc.backOn,0);gl.uniform1i(loc.emb,0);gl.uniform1i(loc.embB,1);gl.uniform1f(loc.bright,1.3);gl.uniform1f(loc.flip,1);gl.uniform4fv(loc.bounds,[-1.5*PIECE_W,-PIECE_H,3*PIECE_W,2*PIECE_H]);

    //  05. 정점 위치·법선·인덱스를 GPU 버퍼로 업로드하는 공통 함수
    function mesh(pos,nor,idx){
        var a=gl.createBuffer(),b=gl.createBuffer(),e=gl.createBuffer();
        gl.bindBuffer(gl.ARRAY_BUFFER,a); gl.bufferData(gl.ARRAY_BUFFER,new Float32Array(pos),gl.STATIC_DRAW);
        gl.bindBuffer(gl.ARRAY_BUFFER,b); gl.bufferData(gl.ARRAY_BUFFER,new Float32Array(nor),gl.STATIC_DRAW);
        gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER,e); gl.bufferData(gl.ELEMENT_ARRAY_BUFFER,new Uint16Array(idx),gl.STATIC_DRAW);
        return {a:a,b:b,e:e,count:idx.length};
    }

    //  06. 퍼즐 조각 메시 생성: 탭(+1)과 홈(-1)이 서로 맞물리는 하나의 압출 입체 실루엣
    /* A single watertight extruded silhouette per piece. Positive edges are tabs,
       negative edges are matching sockets. Adjacent pieces share one exact profile. */
    //  퍼즐 조각의 폭/높이/두께/돌출 크기와 곡선 분할 수
    //  탭 모양 제어점(변 중앙 기준, KNOB 배율): 좁은 목 + 둥근 머리, 좌우 대칭이라 이웃 조각과 정확히 맞물림
    var KNOB_PTS=[[-1.3,0],[-.8,0],[-.42,.14],[-.4,.42],[-.66,.8],[-.42,1.12],[0,1.22],[.42,1.12],[.66,.8],[.4,.42],[.42,.14],[.8,0],[1.3,0]];
    //  변별 시작점·진행 방향·바깥 방향 (상/우/하/좌, 시계 방향)
    var EDGE_FRAMES=[
        [[-PIECE_W/2, PIECE_H/2],[1,0],[0,1],PIECE_W],
        [[ PIECE_W/2, PIECE_H/2],[0,-1],[1,0],PIECE_H],
        [[ PIECE_W/2,-PIECE_H/2],[-1,0],[0,-1],PIECE_W],
        [[-PIECE_W/2,-PIECE_H/2],[0,1],[-1,0],PIECE_H]
    ];
    //  Catmull-Rom 보간: 제어점을 지나는 매끈한 곡선
    function cr(p0,p1,p2,p3,t){
        var t2=t*t,t3=t2*t;
        return [0,1].map(function(k){return .5*((2*p1[k])+(-p0[k]+p2[k])*t+(2*p0[k]-5*p1[k]+4*p2[k]-p3[k])*t2+(-p0[k]+3*p1[k]-3*p2[k]+p3[k])*t3);});
    }
    //  네 변을 이어 한 조각의 폐곡선 윤곽 생성 (모서리 점은 다음 변이 시작점으로 가짐)
    function contour(edges,ids){
        var out=[];
        for(var e=0;e<4;e++){
            var f=EDGE_FRAMES[e],L=f[3],type=edges[e];
            var ctrl=[[0,0]];
            if(type) KNOB_PTS.forEach(function(k){ctrl.push([L/2+k[0]*KNOB,k[1]*KNOB*type]);});
            ctrl.push([L,0]);
            for(var i=0;i<ctrl.length-1;i++){
                var p0=ctrl[Math.max(0,i-1)],p1=ctrl[i],p2=ctrl[i+1],p3=ctrl[Math.min(ctrl.length-1,i+2)];
                var steps=type?SAMPLES:1;
                for(var s2=0;s2<steps;s2++){
                    var q=cr(p0,p1,p2,p3,s2/steps);
                    out.push([f[0][0]+f[1][0]*q[0]+f[2][0]*q[1], f[0][1]+f[1][1]*q[0]+f[2][1]*q[1]]);if(ids)ids.push(e);
                }
            }
        }
        return out;
    }
    //  다각형 방향·외적·점의 삼각형 포함 여부를 계산하는 기하 보조 함수
    function area(poly){var a=0;for(var i=0;i<poly.length;i++){var q=poly[i],r=poly[(i+1)%poly.length];a+=q[0]*r[1]-r[0]*q[1];}return a*.5;}
    function cross(a,b,c){return (b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0]);}
    function inTri(p,a,b,c){var x=cross(a,b,p),y=cross(b,c,p),z=cross(c,a,p);return (x>=-1e-6&&y>=-1e-6&&z>=-1e-6)||(x<=1e-6&&y<=1e-6&&z<=1e-6);}
    //  Ear clipping 방식으로 앞/뒤 면을 삼각형 인덱스로 분할
    function triangulate(poly){
        var order=[]; for(var i=0;i<poly.length;i++) order.push(i);
        if(area(poly)<0) order.reverse();
        var tris=[],guard=0;
        while(order.length>2 && guard++<10000){
            var cut=false;
            for(i=0;i<order.length;i++){
                var ia=order[(i+order.length-1)%order.length],ib=order[i],ic=order[(i+1)%order.length];
                if(cross(poly[ia],poly[ib],poly[ic])<=1e-7) continue;
                var blocked=false;
                for(var j=0;j<order.length;j++){var ip=order[j];if(ip!==ia&&ip!==ib&&ip!==ic&&inTri(poly[ip],poly[ia],poly[ib],poly[ic])){blocked=true;break;}}
                if(!blocked){tris.push(ia,ib,ic);order.splice(i,1);cut=true;break;}
            }
            if(!cut) break;
        }
        return tris;
    }
    //  2D 윤곽을 3D 조각으로: 앞/뒷면 + 둥근 모서리(베벨) + 옆면, 곡선 구간은 법선을 이어 매끈하게 음영
    function puzzleMesh(edges){
        var poly=contour(edges),n=poly.length,ccw=area(poly)>0,i;
        //  선분별 바깥 법선
        var segN=[];
        for(i=0;i<n;i++){
            var a=poly[i],b=poly[(i+1)%n],dx=b[0]-a[0],dy=b[1]-a[1],len=Math.sqrt(dx*dx+dy*dy)||1;
            segN.push(ccw?[dy/len,-dx/len]:[-dy/len,dx/len]);
        }
        //  꼭짓점 법선: 완만한 곳은 평균(매끈), 직각 모서리는 각 면 법선 유지(날카로움)
        var vN=[],sharp=[];
        for(i=0;i<n;i++){
            var pN=segN[(i+n-1)%n],cN=segN[i],sx=pN[0]+cN[0],sy=pN[1]+cN[1],sl=Math.sqrt(sx*sx+sy*sy)||1;
            vN.push([sx/sl,sy/sl]); sharp.push(pN[0]*cN[0]+pN[1]*cN[1]<.6);
        }
        //  베벨 안쪽 윤곽: 꼭짓점 법선 방향으로 BEVEL만큼 들임(마이터 보정)
        var inner=poly.map(function(p,k){var d=Math.max(.5,vN[k][0]*segN[k][0]+vN[k][1]*segN[k][1]);return [p[0]-vN[k][0]*BEVEL/d,p[1]-vN[k][1]*BEVEL/d];});
        var pos=[],nor=[],idx=[],cap=triangulate(inner),Z=DEPTH,ZB=DEPTH-BEVEL;
        //  앞/뒷면
        for(i=0;i<n;i++){pos.push(inner[i][0],inner[i][1],Z);nor.push(0,0,1);}
        for(i=0;i<n;i++){pos.push(inner[i][0],inner[i][1],-Z);nor.push(0,0,-1);}
        for(i=0;i<cap.length;i+=3){idx.push(cap[i],cap[i+1],cap[i+2]);idx.push(n+cap[i+2],n+cap[i+1],n+cap[i]);}
        //  띠 하나(4점)를 추가하는 보조 함수: 두 링 사이를 선분 단위로 연결
        function band(ringA,zA,ringB,zB,normalA,normalB){
            for(var k=0;k<n;k++){
                var k2=(k+1)%n,o=pos.length/3;
                var na=sharp[k]?segN[k]:vN[k],nb=sharp[k2]?segN[k]:vN[k2];
                pos.push(ringA[k][0],ringA[k][1],zA, ringA[k2][0],ringA[k2][1],zA, ringB[k2][0],ringB[k2][1],zB, ringB[k][0],ringB[k][1],zB);
                [normalA(na),normalA(nb),normalB(nb),normalB(na)].forEach(function(v){nor.push(v[0],v[1],v[2]);});
                idx.push(o,o+1,o+2,o,o+2,o+3);
            }
        }
        function tilt(z){return function(v){var l=Math.sqrt(v[0]*v[0]+v[1]*v[1]+z*z);return [v[0]/l,v[1]/l,z/l];};}
        band(inner,Z,poly,ZB,tilt(1.6),tilt(.35));       // 앞쪽 둥근 모서리
        band(poly,ZB,poly,-ZB,tilt(0),tilt(0));          // 옆면
        band(poly,-ZB,inner,-Z,tilt(-.35),tilt(-1.6));   // 뒤쪽 둥근 모서리
        return mesh(pos,nor,idx);
    }

    //  구멍: 조각 윤곽 그대로의 입구(z=ZT)와 바닥(z=ZB). 내벽은 바깥 테두리 변(outer[e])에만, 법선은 구멍 안쪽을 향함
    var HOLE_TOP=DEPTH+.01,HOLE_BOTTOM=-.42;
    function puzzleHole(edges,outer){
        var ids=[],poly=contour(edges,ids),n=poly.length,cap=triangulate(poly),ccw=area(poly)>0,i;
        var capPos=[],capNor=[],pos=[],nor=[],idx=[];
        for(i=0;i<n;i++){capPos.push(poly[i][0],poly[i][1],HOLE_TOP);capNor.push(0,0,1);pos.push(poly[i][0],poly[i][1],HOLE_BOTTOM);nor.push(0,0,1);}
        for(i=0;i<n;i++){
            if(!outer[ids[i]])continue;
            var a=poly[i],b=poly[(i+1)%n],dx=b[0]-a[0],dy=b[1]-a[1],len=Math.sqrt(dx*dx+dy*dy)||1;
            var nx=ccw?-dy/len:dy/len,ny=ccw?dx/len:-dx/len,o=pos.length/3;
            pos.push(a[0],a[1],HOLE_TOP,b[0],b[1],HOLE_TOP,b[0],b[1],HOLE_BOTTOM,a[0],a[1],HOLE_BOTTOM);
            for(var k=0;k<4;k++)nor.push(nx,ny,0);
            idx.push(o,o+1,o+2,o,o+2,o+3);
        }
        return {cap:mesh(capPos,capNor,cap.slice()),hole:mesh(pos,nor,cap.concat(idx))};
    }

    //  07. 3D 행렬 계산: 이동·크기·X/Y/Z 회전·원근 투영·최종 모델 행렬
    /* Explicit column-major transforms keep the parent animation auditable. */
    function identity(){return new Float32Array([1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1]);}
    function mul(a,b){var o=new Float32Array(16);for(var c=0;c<4;c++)for(var r=0;r<4;r++)o[c*4+r]=a[r]*b[c*4]+a[4+r]*b[c*4+1]+a[8+r]*b[c*4+2]+a[12+r]*b[c*4+3];return o;}
    function T(x,y,z){var m=identity();m[12]=x;m[13]=y;m[14]=z;return m;}
    function S(x,y,z){var m=identity();m[0]=x;m[5]=y;m[10]=z;return m;}
    function RX(v){var c=Math.cos(v),s=Math.sin(v);return new Float32Array([1,0,0,0,0,c,s,0,0,-s,c,0,0,0,0,1]);}
    function RY(v){var c=Math.cos(v),s=Math.sin(v);return new Float32Array([c,0,-s,0,0,1,0,0,s,0,c,0,0,0,0,1]);}
    function RZ(v){var c=Math.cos(v),s=Math.sin(v);return new Float32Array([c,s,0,0,-s,c,0,0,0,0,1,0,0,0,0,1]);}
    function P(f,a,n,z){var t=1/Math.tan(f/2),q=1/(n-z);return new Float32Array([t/a,0,0,0,0,t,0,0,0,0,(z+n)*q,-1,0,0,2*z*n*q,0]);}
    function M(pos,rot,scale){return mul(mul(mul(mul(T(pos[0],pos[1],pos[2]),RY(rot[1])),RX(rot[0])),RZ(rot[2])),S(scale[0],scale[1],scale[2]));}

    //  08. 여섯 조각의 상/우/하/좌 변 형태 정의: 0=직선, 1=돌출 탭, -1=들어간 홈
    var edgeSets=[
        [0, 1, 1,0], [0,-1,-1,-1], [0,0, 1, 1],
        [-1,-1,0,0], [1, 1,0, 1], [-1,0,0,-1]
    ];
    //  바깥 테두리 변 [조각, 변] 목록: 시계 방향 (setOuterEdges 순서)
    var OUTER_EDGES=[[0,0],[1,0],[2,0],[2,1],[5,1],[5,2],[4,2],[3,2],[3,3],[0,3]];
    //  조립 완료 시 각 퍼즐 조각이 도착할 3×2 목표 좌표
    var targets=[[-PIECE_W,PIECE_H/2,0],[0,PIECE_H/2,0],[PIECE_W,PIECE_H/2,0],[-PIECE_W,-PIECE_H/2,0],[0,-PIECE_H/2,0],[PIECE_W,-PIECE_H/2,0]];
    //  조립 전 화면 밖/주변에 흩어진 각 조각의 시작 좌표
    var starts=[[-3.45,2.35,-1.4],[3.2,2.5,-1.8],[3.65,.25,-1.2],[-3.55,-2.15,-1.5],[.1,-2.75,-1.8],[3.4,-2.1,-1.25]];
    //  각 조각의 RGB 색상(기본): 메인과 같은 스카이 블루 단색 톤. 좌상·상중·우상·좌하·하중·우하
    var colors = ['#7DB4F5','#3B82F6','#93C5FD','#60A5FA','#2F74E8','#A9D0FF'].map(function(h){var v=parseInt(h.slice(1),16);return [(v>>16&255)/255,(v>>8&255)/255,(v&255)/255];});
    //  실제 퍼즐 객체 생성: mesh, 목표/시작 위치, 보간 진행률, 목표 상태, 초기 회전값 보관
    var pieces=targets.map(function(t,i){return{mesh:puzzleMesh(edgeSets[i]),t:t,s:starts[i],p:0,goal:0,spin:[-.6+i*.11,i%2?-.95:.92,i*.22]};});
    //  조각별 바깥 테두리 변 여부 (구멍 내벽용)
    function outerMask(i){return [0,1,2,3].map(function(e){return OUTER_EDGES.some(function(k){return k[0]===i&&k[1]===e;});});}
    pieces.forEach(function(p,i){p.holeMesh=puzzleHole(edgeSets[i],outerMask(i));});
    //  sets: 현재 6조각 변 배열. 반환: 판 조각 [{mesh, x, y}] (퍼즐 칸 6개는 비워 둠)
    var INNER={'1,1':0,'1,2':1,'1,3':2,'2,1':3,'2,2':4,'2,3':5};
    function buildBoard(sets){
        var h=[],v=[],r,c;
        for(r=0;r<4;r++){h[r]=[];v[r]=[];for(c=0;c<5;c++){h[r][c]=(r*7+c*3)%2?1:-1;v[r][c]=(r*5+c*3)%2?-1:1;}}
        Object.keys(INNER).forEach(function(key){
            var g=key.split(',').map(Number),e=sets[INNER[key]];
            h[g[0]-1][g[1]]=-e[0];v[g[0]][g[1]]=e[1];h[g[0]][g[1]]=e[2];v[g[0]][g[1]-1]=-e[3];
        });
        var out=[];
        for(r=0;r<4;r++)for(c=0;c<5;c++){
            if(INNER[r+','+c]!=null)continue;
            var edges=[r>0?-h[r-1][c]:0,c<4?v[r][c]:0,r<3?h[r][c]:0,c>0?-v[r][c-1]:0];
            out.push({mesh:puzzleMesh(edges),x:(c-2)*PIECE_W,y:(1.5-r)*PIECE_H});
        }
        return out;
    }
    var board=buildBoard(edgeSets),curSets=edgeSets;
    //  격자 칸 (r,c): 퍼즐 6칸이 r 0~1, c 0~2. 칸 중심 x=(c-1)·폭, y=(0.5-r)·높이
    //  hE(r,c): r행과 r+1행 사이 변(윗 칸 아랫변 기준), vE(r,c): c열과 c+1열 사이 변(왼 칸 오른변 기준). 퍼즐 칸 변은 퍼즐 모양을 따름
    function gridEdges(sets){
        var H={},V={};
        sets.forEach(function(e,i){var r=i<3?0:1,c=i%3;H[(r-1)+','+c]=-e[0];V[r+','+c]=e[1];H[r+','+c]=e[2];V[r+','+(c-1)]=-e[3];});
        function hash(a,b,m){return (((a*7+b*13+m)%2)+2)%2?1:-1;}
        return {h:function(r,c){var k=r+','+c;return k in H?H[k]:hash(r,c,0);},v:function(r,c){var k=r+','+c;return k in V?V[k]:hash(r,c,1);}};
    }
    var grid={key:'',tiles:[]},SLOT_INSET=.07;
    //  조각 윤곽선 버퍼 (LINE_LOOP용)
    function lineMesh(edges){
        var pts=contour(edges),pos=[];pts.forEach(function(q){pos.push(q[0],q[1],0);});
        var b=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,b);gl.bufferData(gl.ARRAY_BUFFER,new Float32Array(pos),gl.STATIC_DRAW);
        return {b:b,count:pts.length};
    }
    function buildGrid(c0,c1,r0,r1){
        var E=gridEdges(curSets),tiles=curSets.map(function(e,i){return {line:lineMesh(e),x:(i%3-1)*PIECE_W,y:i<3?PIECE_H/2:-PIECE_H/2};});
        for(var r=r0;r<=r1;r++)for(var c=c0;c<=c1;c++){
            if(r>=0&&r<=1&&c>=0&&c<=2)continue;
            tiles.push({line:lineMesh([-E.h(r-1,c),E.v(r,c),E.h(r,c),-E.v(r,c-1)]),x:(c-1)*PIECE_W,y:(.5-r)*PIECE_H});
        }
        return tiles;
    }
    //  벽: 퍼즐보다 넉넉한 판 하나 (입구 모양은 스텐실로 뚫음)
    var WALL_W=3*PIECE_W+1.3,WALL_H=2*PIECE_H+1.1,wallMesh=puzzleMesh([0,0,0,0]);

    function hexRgb(h){var v=parseInt(h.slice(1),16);return [(v>>16&255)/255,(v>>8&255)/255,(v&255)/255];}
    //  엠블럼/글자 마스크 캔버스: 완성 퍼즐(3×2) 비율
    function maskCanvas(){var cv=document.createElement('canvas');cv.width=1024;cv.height=Math.round(1024*2*PIECE_H/(3*PIECE_W));return cv;}
    function upload(cv,unit){
        var tex=gl.createTexture();gl.activeTexture(gl.TEXTURE0+unit);gl.bindTexture(gl.TEXTURE_2D,tex);
        gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,gl.RGBA,gl.UNSIGNED_BYTE,cv);
        gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.LINEAR);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,gl.LINEAR);
        gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE);
        gl.uniform2fv(loc.embTexel,[1.5/cv.width,1.5/cv.height]);
    }
    canvas._puzzleLook={
        //  hexList: 좌상·상중·우상·좌하·하중·우하 6색, bright: 밝기 배율
        setColors:function(hexList,bright){colors=hexList.map(hexRgb);gl.uniform1f(loc.bright,bright||1.3);},
        //  img: 알파 마스크 이미지(null이면 끔), scale: 퍼즐 높이 대비 크기. 앞면 가운데에 놓음
        setEmblem:function(img,scale){
            if(!img){gl.uniform1f(loc.embOn,0);embState[0]=0;return;}
            var cv=maskCanvas(),lh=cv.height*(scale||.8),lw=lh*img.width/img.height;
            cv.getContext('2d').drawImage(img,(cv.width-lw)/2,(cv.height-lh)/2,lw,lh);
            upload(cv,0);gl.uniform1f(loc.embOn,1);embState[0]=1;
        },
        //  뒷면 가운데에 글자(null이면 끔): 완성 퍼즐 폭의 80%에 맞춤
        setBackText:function(text,weight){
            if(!text){gl.uniform1f(loc.backOn,0);embState[1]=0;return;}
            var cv=maskCanvas(),ctx=cv.getContext('2d'),font=function(px){return (weight||800)+' '+px+'px Pretendard, sans-serif';};
            ctx.font=font(100);var px=Math.min(cv.height*.42,100*cv.width*.8/ctx.measureText(text).width);
            ctx.font=font(px);ctx.textAlign='center';ctx.textBaseline='middle';ctx.fillStyle='#fff';ctx.fillText(text,cv.width/2,cv.height/2);
            upload(cv,1);gl.uniform1f(loc.backOn,1);embState[1]=1;
        },
        //  단계 이동(외부 버튼용): 6단계 이후/이전은 초기 상태로 순환, 돌아가 있던 각도도 제자리로
        //  el: 퍼즐이 따라갈 화면 요소(완성 퍼즐 폭 = 요소 폭, 중심 = 요소 중심). 요소가 숨겨지면 기본 위치
        setAnchor:function(el){anchor=el;},
        //  떠다니는 모션 속도 배율(1=기본, 0=멈춤)
        setFloatSpeed:function(v){floatSpeed=v;},
        //  빈 자리(구멍) 스타일: 'puzzle' 퍼즐 모양 구멍 / 'board' 퍼즐판의 빈칸 / 'wall' 벽에 난 구멍 / 'grid' 배경 전체 퍼즐 격자의 빈칸 / null 없음
        setGhost:function(style){ghost=style||null;},
        //  list: 흩어진 조각 6개의 화면 위치 [[가로,세로], ...](캔버스 기준 -1~1). null이면 퍼즐 주변 기본 위치
        //  size: 떠다니는 조각 크기(퍼즐 공간 단위, 기본 .5)
        //  rel=true: size를 맞춘 조각 대비 비율로 (작은 캔버스용)
        setRoam:function(list,size,rel){roam=list||null;roamSize=size||.5;roamRel=!!rel;pieces.forEach(function(p,i){p.custom=false;if(!list)p.s=starts[i];});},
        //  떠다니는 조각의 화면 폭(px): 바깥에서 겹침 없이 배치할 때 사용
        roamPieceSize:function(){return roamSize*PIECE_W*canvas.clientWidth/(2*view.hw)*view.camD/(view.camD-.45);},
        //  바깥 테두리 10변 모양: 좌상 조각 윗변부터 시계 방향(윗변 3 → 오른쪽 2 → 아랫변 3 → 왼쪽 2), 1=돌출 -1=홈 0=직선
        setOuterEdges:function(vals){
            var sets=edgeSets.map(function(e){return e.slice();});
            OUTER_EDGES.forEach(function(k,n){sets[k[0]][k[1]]=vals[n]||0;});
            pieces.forEach(function(p,i){p.mesh=puzzleMesh(sets[i]);p.holeMesh=puzzleHole(sets[i],outerMask(i));});
            board=buildBoard(sets);curSets=sets;grid.key='';
        },
        //  현재 단계의 목표 상태로 즉시 이동(애니메이션 없이, 시안 확인용)
        snap:function(){pieces.forEach(function(p){p.p=p.goal;});},
        next:function(){tx=REST_X;ty=REST_Y;nextPiece();},
        //  idx: 맞춰 둘 조각 번호 목록(0~5, 좌상부터) — 실제 진행도를 원하는 조각으로 보여줄 때
        setAssembled:function(idx){pieces.forEach(function(p,i){p.goal=idx.indexOf(i)>=0?1:0;});order=idx.map(function(i){return pieces[i];});showStage();},
        prev:function(){tx=REST_X;ty=REST_Y;prevPiece();},
        //  드래그 동작: 'rotate' 전체 회전 / 'piece' 조각 잡아 끼우기 / 'tilt' 마우스 따라 기울기 / 'none' 없음
        setDragMode:function(m){dragMode=m;tx=REST_X;ty=REST_Y;},
        //  on=false: 빈 공간 클릭으로 조각을 맞추지 않음(대시보드처럼 실제 진행도를 보여줄 때)
        setClickable:function(on){clickable=on!==false;},
        //  sec초마다 다음 조각을 자동으로 맞춤(다 맞추면 처음부터). 0이면 끔
        setAuto:function(sec){autoSec=sec;restartAuto();}
    };

    //  09. 장면 상태: 현재 단계, 시간, 전체 회전, 드래그 여부, 포인터 위치, 모션 감소 설정
    var REST_X=-.17,REST_Y=.23,anchor=null,autoTimer=0,autoSec=0,ft=0,floatSpeed=1,ghost=null,roam=null,roamSize=.5,roamRel=false,clickable=true,order=[],dragMode='rotate',grabbed=null,view={hw:1,hh:1,ax:0,ay:0,k:1,camD:7.5},embState=[0,0],HOLE=.42;
    //  구멍 색: 안쪽(HERO 남색보다 어둡게) / 입구 단면 / 퍼즐판·벽 표면(HERO 남색보다 살짝 밝게)
    var HOLE_COL=hexRgb('#060E1A'),LIP_COL=hexRgb('#22395C'),SURFACE_COL=hexRgb('#15283F'),SLOT_COL=hexRgb('#15294A'),SLOT_SHADOW=hexRgb('#07101D');
    var stage=-1,started=Infinity,last=performance.now(),rx=REST_X,ry=REST_Y,tx=rx,ty=ry,drag=false,moved=false,px=0,py=0;
    var analysisStage=-1,analysisTimer=0,analysisScatterTimer=0,analysisObserver=null,resultTimer=0;
    var reduce=window.matchMedia&&window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    //  홀로그램 전환 상태: 연속 클릭 중에는 마지막 요청 단계를 보관해 순서가 꼬이지 않게 처리
    var currentHologramIndex=0,hologramTransitioning=false,pendingHologramIndex=null,hologramTimer=0;
    var hologramTransitionDuration=reduce?160:780;
    function hologramStageNumber(index){return String(index+1).padStart(2,'0');}
    //  단계 번호는 이전/다음 값을 겹쳐 올려 실제 카운트업처럼 전환하고, 하단 점도 함께 이동
    function updateHologramIndicator(index,animate){
        if(hologramNumber){
            var nextNumber=hologramStageNumber(index);
            var previousNumber=hologramNumber.textContent.trim()||hologramStageNumber(currentHologramIndex);
            hologramNumber.setAttribute('data-hologram-from',previousNumber);
            hologramNumber.setAttribute('data-hologram-to',nextNumber);
            hologramNumber.classList.toggle('is-counting',Boolean(animate&&!reduce&&previousNumber!==nextNumber));
            if(!animate||reduce) hologramNumber.textContent=nextNumber;
        }
        hologramDots.forEach(function(dot,i){dot.classList.toggle('is-active',i===index);});
    }
    //  에너지 펄스 → 약한 수평 슬라이스 → 스캔 위치를 따라 다음 화면이 공개되는 780ms 전환
    function switchHologram(nextIndex){
        if(!hologram||!hologramPanels.length) return;
        nextIndex=Math.max(0,Math.min(hologramPanels.length-1,nextIndex));
        if(hologramTransitioning){pendingHologramIndex=nextIndex;return;}
        if(nextIndex===currentHologramIndex){updateHologramIndicator(nextIndex);return;}

        var outgoing=hologramPanels[currentHologramIndex];
        var incoming=hologramPanels[nextIndex];
        hologramTransitioning=true;
        pendingHologramIndex=null;
        window.clearTimeout(hologramTimer);
        hologram.classList.add('is-switching');
        outgoing.setAttribute('data-hologram-state','outgoing');
        outgoing.setAttribute('aria-hidden','true');
        incoming.setAttribute('data-hologram-state','incoming');
        incoming.setAttribute('aria-hidden','false');
        updateHologramIndicator(nextIndex,true);

        hologramTimer=window.setTimeout(function(){
            outgoing.setAttribute('data-hologram-state','hidden');
            outgoing.classList.remove('is-active');
            incoming.setAttribute('data-hologram-state','active');
            incoming.classList.add('is-active');
            currentHologramIndex=nextIndex;
            hologramTransitioning=false;
            hologram.classList.remove('is-switching');
            if(hologramNumber){
                hologramNumber.textContent=hologramStageNumber(nextIndex);
                hologramNumber.classList.remove('is-counting');
            }
            hologram.dispatchEvent(new CustomEvent('hologram-transition-end',{detail:{index:nextIndex,number:hologramStageNumber(nextIndex)}}));

            if(pendingHologramIndex!==null&&pendingHologramIndex!==currentHologramIndex){
                var queuedIndex=pendingHologramIndex;
                pendingHologramIndex=null;
                switchHologram(queuedIndex);
            }else{
                pendingHologramIndex=null;
            }
        },hologramTransitionDuration);
    }
    //  클릭 단계 변경: 조립할 조각 수를 정하고 단계 문구/홀로그램/진행 바를 갱신
    function setStage(next){
        pieces.forEach(function(p,i){p.goal=i<=next?1:0;p.custom=false;p.dragPos=null;});
        order=pieces.filter(function(p){return p.goal;});
        showStage();
    }
    //  맞춰진 조각 수로 단계 문구·홀로그램·진행 바 갱신
    function showStage(){
        stage=order.length-1;restartAuto();
        var data=stages[Math.max(0,stage)];
        if(label){label.parentNode.classList.add('is-changing');window.setTimeout(function(){label.textContent=stage<0?'빈 공간을 눌러 시작':data.label;label.parentNode.classList.remove('is-changing');},140);}
        switchHologram(stage<0?0:stage);
        if(bar)bar.style.width=(stage<0?0:(stage+1)/6*100)+'%';
    }
    //  자동 맞추기 카운트다운: 넘어갈 때마다(자동·클릭·화살표·드래그로 끼움) 처음부터 다시
    function restartAuto(){
        window.clearTimeout(autoTimer);autoTimer=0;
        if(autoSec>0&&!grabbed)autoTimer=window.setTimeout(function(){if(document.hidden){restartAuto();return;}nextPiece();},autoSec*1000);
    }
    function nextPiece(){
        var p=pieces.filter(function(q){return !q.goal;})[0];
        if(!p){setStage(-1);return;}
        p.goal=1;order.push(p);showStage();
    }
    function prevPiece(){
        var p=order.pop();
        if(!p){setStage(5);return;}
        p.goal=0;showStage();
    }
    function setAnalysisStage(next){
        next=Math.max(0,Math.min(4,Number(next)||0));
        if(next===analysisStage)return;
        analysisStage=next;
        window.clearTimeout(analysisTimer);
        window.clearTimeout(analysisScatterTimer);
        canvas.classList.remove('is-stage-assembled');
        pieces.forEach(function(p){p.goal=0;});
        if(next<=0)return;
        pieces.forEach(function(p){p.goal=1;});
        analysisTimer=window.setTimeout(function(){
            canvas.classList.add('is-stage-assembled');
        },reduce?80:850);
        analysisScatterTimer=window.setTimeout(function(){
            canvas.classList.remove('is-stage-assembled');
            pieces.forEach(function(p){p.goal=0;});
        },reduce?420:2300);
    }
    //  10. WebGL 렌더링 보조 함수: 버퍼 바인딩, 한 조각 그리기, canvas 해상도 동기화
    function bind(m){gl.bindBuffer(gl.ARRAY_BUFFER,m.a);gl.enableVertexAttribArray(loc.p);gl.vertexAttribPointer(loc.p,3,gl.FLOAT,false,0,0);gl.bindBuffer(gl.ARRAY_BUFFER,m.b);gl.enableVertexAttribArray(loc.n);gl.vertexAttribPointer(loc.n,3,gl.FLOAT,false,0,0);gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER,m.e);}
    function draw(m,model,color,offset){bind(m);gl.uniformMatrix4fv(loc.m,false,model);gl.uniform3fv(loc.c,color);gl.uniform2fv(loc.off,offset);gl.drawElements(gl.TRIANGLES,m.count,gl.UNSIGNED_SHORT,0);}
    function resize(){var d=Math.min(devicePixelRatio||1,2),w=Math.max(1,canvas.clientWidth*d|0),h=Math.max(1,canvas.clientHeight*d|0);if(canvas.width!==w||canvas.height!==h){canvas.width=w;canvas.height=h;gl.viewport(0,0,w,h);}}

    //  11. 사용자 상호작용 대상: HERO 빈 공간에서만 클릭/드래그를 받고 버튼·카드·헤더는 제외
    var interaction=document.querySelector('.hero--interactive')||canvas;
    //  클릭 가능한 빈 영역인지 판별
    function interactiveTarget(target){return !target.closest('a,button,input,select,textarea,.hero__copy,.hero__card-wrap,.user-menu,.site-header');}
    //  단순 클릭이면 다음 조립 단계로 이동하고, 6단계 이후에는 초기 상태로 순환
    //  드래그는 HERO 문구 위에서 시작해도 되게(링크·버튼·카드·헤더만 제외), 글자 선택은 막음
    function dragTarget(target){return !target.closest('a,button,input,select,textarea,.hero__card-wrap,.user-menu,.site-header');}
    //  화면 좌표 → 퍼즐 공간 좌표(깊이 z의 평면 위). 전체 기울기는 무시한 근사
    function toPuzzle(cx,cy,z){
        var r=canvas.getBoundingClientRect(),nx=(cx-r.left)/r.width*2-1,ny=1-(cy-r.top)/r.height*2;
        var f=(view.camD-z*view.k)/view.camD;
        return [(nx*view.hw*f-view.ax)/view.k,(ny*view.hh*f-view.ay)/view.k];
    }
    //  포인터 아래 가장 가까운 조각
    function pickPiece(cx,cy){
        var best=null,bd=Infinity;
        pieces.forEach(function(p){
            if(!p.cur)return;
            var q=toPuzzle(cx,cy,p.cur[2]),d=Math.hypot(q[0]-p.cur[0],q[1]-p.cur[1]);
            if(d<PIECE_W*.6*p.sc&&d<bd){bd=d;best=p;}
        });
        return best;
    }
    //  pointerdown: 모드별 드래그 시작 (rotate: 전체 회전 / piece: 조각 잡기)
    if(!analysisMode&&!resultMode) interaction.addEventListener('pointerdown',function(e){
        if(!dragTarget(e.target))return;
        moved=false;px=e.clientX;py=e.clientY;
        if(dragMode==='rotate')drag=true;
        else if(dragMode==='piece'){grabbed=pickPiece(e.clientX,e.clientY);if(!grabbed)return;window.clearTimeout(autoTimer);}
        else return;
        e.preventDefault();
        if(interaction.setPointerCapture)interaction.setPointerCapture(e.pointerId);
    });
    //  pointermove: rotate는 회전, piece는 잡은 조각 이동, tilt는 누르지 않아도 마우스 위치만큼 살짝 기울기
    if(!analysisMode&&!resultMode) interaction.addEventListener('pointermove',function(e){
        var dx=e.clientX-px,dy=e.clientY-py;
        if(dragMode==='tilt'){
            var r=interaction.getBoundingClientRect();
            ty=REST_Y+((e.clientX-r.left)/r.width-.5)*.7;tx=REST_X+((e.clientY-r.top)/r.height-.5)*.5;return;
        }
        if(!drag&&!grabbed)return;
        if(Math.abs(dx)+Math.abs(dy)>3)moved=true;
        if(drag){ty+=dx*.008;tx=Math.max(-.72,Math.min(.5,tx+dy*.008));px=e.clientX;py=e.clientY;}
        if(grabbed&&moved){var z=grabbed.s[2],q=toPuzzle(e.clientX,e.clientY,z);grabbed.dragPos=[q[0],q[1],z];}
    });
    //  pointerup: piece 모드는 자리 근처에 놓으면 쏙 들어가고, 아니면 놓은 자리에 머묾
    function release(){
        drag=false;
        var p=grabbed;grabbed=null;
        if(!p||!p.dragPos){if(p)restartAuto();return;}
        var near=Math.hypot(p.dragPos[0]-p.t[0],p.dragPos[1]-p.t[1])<PIECE_W*.55||(Math.abs(p.dragPos[0])<1.5*PIECE_W+.25&&Math.abs(p.dragPos[1])<PIECE_H+.25);
        p.s=p.dragPos;p.dragPos=null;p.custom=true;p.p=0;
        if(near&&!p.goal){p.goal=1;order.push(p);}
        else if(!near&&p.goal){p.goal=0;order.splice(order.indexOf(p),1);}
        showStage();
    }
    if(!analysisMode&&!resultMode) interaction.addEventListener('pointerup',release);
    if(!analysisMode&&!resultMode) interaction.addEventListener('pointercancel',release);
    //  단순 클릭: 드래그로 돌아가 있으면 제자리로만 복귀, 제자리면 다음 조립 단계(6단계 이후 초기 상태로 순환)
    if(!analysisMode&&!resultMode) interaction.addEventListener('click',function(e){
        if(!clickable||moved||!interactiveTarget(e.target))return;
        if(dragMode==='rotate'&&Math.abs(tx-REST_X)+Math.abs(ty-REST_Y)>.02){tx=REST_X;ty=REST_Y;return;}
        nextPiece();
    });

    //  12. 매 프레임 실행되는 WebGL 렌더 루프
    function render(now){
        if(!canvas.isConnected){
            window.clearTimeout(analysisTimer);
            window.clearTimeout(analysisScatterTimer);
            window.clearInterval(resultTimer);
            if(analysisObserver)analysisObserver.disconnect();
            return;
        }
        //  canvas 크기 보정, 프레임 시간 계산, 각 조각 조립 진행률과 전체 회전값을 부드럽게 보간
        resize();
        var dt=Math.min(40,now-last)/1000; last=now; ft+=dt*1000*floatSpeed;
        pieces.forEach(function(p){
            var duration=analysisMode?(p.goal?.85:2.4):1.05;
            var stepSize=dt/duration;
            p.p+=Math.max(-stepSize,Math.min(stepSize,p.goal-p.p));
        });
        var follow=1-Math.exp(-dt*5); rx+=(tx-rx)*follow; ry+=(ty-ry)*follow;
        //  깊이 테스트 활성화, 양면 표시, 투명 배경으로 프레임 초기화
        gl.enable(gl.DEPTH_TEST); gl.disable(gl.CULL_FACE);
        gl.clearColor(0,0,0,0); gl.clear(gl.COLOR_BUFFER_BIT|gl.DEPTH_BUFFER_BIT|gl.STENCIL_BUFFER_BIT);
        //  화면 비율에 맞는 원근 투영 행렬과 카메라 거리 설정
        var aspect=canvas.width/canvas.height;
        var camD=7.5,fov=Math.PI/4.2;
        gl.uniformMatrix4fv(loc.pv,false,mul(P(fov,aspect,.1,100),T(0,0,-camD)));
        //  앵커 요소가 있으면 그 자리·크기에 맞춰 퍼즐 전체를 이동/확대 (z=0 평면 기준)
        var place=identity(),ar=anchor&&anchor.getBoundingClientRect();
        var hh=camD*Math.tan(fov/2),hw=hh*aspect,ax=0,ay=0,k=1;
        if(ar&&ar.width>0){
            var cr=canvas.getBoundingClientRect();
            ax=((ar.left+ar.width/2-cr.left)/cr.width*2-1)*hw;ay=(1-(ar.top+ar.height/2-cr.top)/cr.height*2)*hh;k=ar.width/cr.width*2*hw/(3*PIECE_W);
            place=mul(T(ax,ay,0),S(k,k,k));
        }
        view={hw:hw,hh:hh,ax:ax,ay:ay,k:k,camD:camD};

        //  조립 상태와 무관하게 계속 유지되는 전체 퍼즐의 부유·호흡·미세 회전 모션
        /* Perpetual parent motion: never multiplied by assembly progress. It remains
           active in READY, assembling, complete, manual-drag and paused autoplay states. */
        var idle=reduce ? .25 : 1;
        var floatY=Math.sin(ft*.00072)*.07*idle;
        var driftX=Math.cos(ft*.00047)*.035*idle;
        var breathe=1+Math.sin(ft*.00061)*.009*idle;
        var globalRot=analysisMode
            ? [rx+Math.sin(ft*.00031)*.055*idle,ry+ft*.0001*idle,Math.sin(ft*.00024)*.03*idle]
            : [rx+Math.sin(ft*.00039)*.012*idle,ry+Math.cos(ft*.00033)*.016*idle,Math.sin(ft*.00028)*.008*idle];

        //  빈 자리(회전·부유 없이 정면 고정). 엠블럼은 잠시 끔
        //  puzzle/wall: 입구 모양을 스텐실로 찍고 그 안에서만 바닥·내벽을 그림 (wall은 입구 밖에 벽판)
        //  board: 퍼즐 칸을 비운 판 조각들 + 바닥 → 판 조각 옆면이 구멍 벽이 됨
        if(ghost){
            gl.uniform1f(loc.embOn,0);gl.uniform1f(loc.backOn,0);
            var off=function(p){return [p.t[0],p.t[1]];};
            if(ghost==='grid'){
                //  화면을 덮는 칸 범위 (바뀔 때만 다시 만듦)
                var c0=Math.floor((-hw-ax)/k/PIECE_W+1)-1,c1=Math.ceil((hw-ax)/k/PIECE_W+1)+1,r0=Math.floor(.5-(hh-ay)/k/PIECE_H)-1,r1=Math.ceil(.5-(-hh-ay)/k/PIECE_H)+1,key=[c0,c1,r0,r1].join();
                if(grid.key!==key){grid.key=key;grid.tiles=buildGrid(c0,c1,r0,r1);}
                //  빈칸: 입구 모양 스텐실 안에 어두운 그림자 + 오른쪽 아래로 살짝 밀린 바닥 → 위·왼쪽 가장자리에 안쪽 그림자가 생겨 파인 것처럼
                gl.enable(gl.STENCIL_TEST);
                gl.colorMask(false,false,false,false);gl.depthMask(false);
                gl.stencilFunc(gl.ALWAYS,1,255);gl.stencilOp(gl.KEEP,gl.KEEP,gl.REPLACE);
                pieces.forEach(function(p){draw(p.holeMesh.cap,mul(place,T(p.t[0],p.t[1],0)),SLOT_COL,off(p));});
                gl.colorMask(true,true,true,true);gl.depthMask(true);
                gl.stencilFunc(gl.EQUAL,1,255);gl.stencilOp(gl.KEEP,gl.KEEP,gl.KEEP);
                pieces.forEach(function(p){draw(p.holeMesh.cap,mul(place,T(p.t[0],p.t[1],-.3)),SLOT_SHADOW,off(p));});
                pieces.forEach(function(p){draw(p.holeMesh.cap,mul(place,T(p.t[0]+SLOT_INSET,p.t[1]-SLOT_INSET,-.29)),SLOT_COL,off(p));});
                gl.disable(gl.STENCIL_TEST);
                //  이음선: 빈칸 바닥과 같은 높이(맞춘 조각에 가려짐), 깊이 기록 없이.
                //  섞지 않고 흰색+4% 알파를 그대로 써서 페이지 위에 CSS 격자선과 같은 방식으로 합성됨
                gl.useProgram(lineProg);gl.disableVertexAttribArray(loc.n);
                gl.uniformMatrix4fv(lineLoc.pv,false,mul(P(fov,aspect,.1,100),T(0,0,-camD)));
                gl.uniform2f(lineLoc.res,canvas.width,canvas.height);
                gl.uniform4f(lineLoc.col,1,1,1,.04*Math.min(devicePixelRatio||1,2));
                gl.depthMask(false);
                gl.enableVertexAttribArray(lineLoc.p);
                grid.tiles.forEach(function(b){
                    gl.bindBuffer(gl.ARRAY_BUFFER,b.line.b);gl.vertexAttribPointer(lineLoc.p,3,gl.FLOAT,false,0,0);
                    gl.uniformMatrix4fv(lineLoc.m,false,mul(place,T(b.x,b.y,.06)));
                    gl.drawArrays(gl.LINE_LOOP,0,b.line.count);
                });
                gl.depthMask(true);
                gl.useProgram(program);
            }else if(ghost==='board'){
                pieces.forEach(function(p){draw(p.holeMesh.cap,mul(place,T(p.t[0],p.t[1],-2*DEPTH)),HOLE_COL,off(p));});
                board.forEach(function(b){draw(b.mesh,mul(place,T(b.x,b.y,.01)),SURFACE_COL,[b.x,b.y]);});
            }else{
                gl.enable(gl.STENCIL_TEST);
                gl.colorMask(false,false,false,false);gl.depthMask(false);
                gl.stencilFunc(gl.ALWAYS,1,255);gl.stencilOp(gl.KEEP,gl.KEEP,gl.REPLACE);
                pieces.forEach(function(p){draw(p.holeMesh.cap,mul(place,T(p.t[0],p.t[1],0)),HOLE_COL,off(p));});
                gl.colorMask(true,true,true,true);
                gl.stencilFunc(gl.NOTEQUAL,1,255);gl.stencilOp(gl.KEEP,gl.KEEP,gl.KEEP);
                if(ghost==='wall'){
                    gl.depthMask(true);
                    draw(wallMesh,mul(place,mul(T(0,0,.01),S(WALL_W/PIECE_W,WALL_H/PIECE_H,1))),SURFACE_COL,[0,0]);
                }else{
                    //  입구 가장자리 단면: 입구보다 살짝 큰 밝은 남색을 스텐실 밖에만(깊이 기록 없이)
                    pieces.forEach(function(p){draw(p.holeMesh.cap,mul(place,mul(T(p.t[0],p.t[1],0),S(1+.05/PIECE_W,1+.05/PIECE_H,1))),LIP_COL,off(p));});
                    gl.depthMask(true);
                }
                gl.stencilFunc(gl.EQUAL,1,255);
                pieces.forEach(function(p){draw(p.holeMesh.hole,mul(place,T(p.t[0],p.t[1],0)),HOLE_COL,off(p));});
                gl.disable(gl.STENCIL_TEST);
            }
            gl.uniform1f(loc.embOn,embState[0]);gl.uniform1f(loc.backOn,embState[1]);
        }
        //  각 조각을 시작 위치에서 목표 위치로 이동시키고, 미조립 상태에는 파동/회전 모션을 추가해 그리기
        pieces.forEach(function(p,i){
            //  떠다니는 깊이는 배경 판(퍼즐판·벽·격자) 앞쪽
            //  (앞쪽 깊이라 원근으로 바깥으로 밀리는 만큼 보정해서 지정한 화면 위치에 오게)
            var rf=(camD-.45)/camD;
            if(roam&&!p.custom)p.s=[(roam[i][0]*hw*rf-ax)/k,(roam[i][1]*hh*rf-ay)/k,.45/k];
            //  잡고 있는 조각은 포인터 위치에 (떠다니는 상태로)
            if(p.dragPos){p.s=p.dragPos;p.p=0;}
            var e=p.p<.5?4*p.p*p.p*p.p:1-Math.pow(-2*p.p+2,3)/2,free=1-e,wave=Math.sin(ft*.00082+i*1.31),sway=Math.cos(ft*.00057+i*.87);
            var pos=[p.s[0]+(p.t[0]-p.s[0])*e+wave*.17*free,p.s[1]+(p.t[1]-p.s[1])*e+sway*.15*free,p.s[2]+(p.t[2]-p.s[2])*e+wave*.12*free];
            var rot=[p.spin[0]*.4*free+wave*.04*free,p.spin[1]*.35*free+sway*.05*free,p.spin[2]*.4*free+wave*.03*free];
            //  roam: 떠다닐 땐 화면 기준 일정 크기의 장식 조각, 자리에 들어가며 구멍 크기로
            var sc=roam?(roamRel?roamSize:roamSize/k)*(1-e)+e:1;p.sc=sc;p.cur=pos;
            //  메인(앵커 있음): 흩어진 조각은 제자리에서 회전, 맞춰질수록 퍼즐 중심 기준 회전. 구멍이 있으면 맞춰질수록 구멍에 고정
            //  다른 페이지(앵커 없음): 기존처럼 퍼즐 전체가 함께 회전
            var gk=ghost?free:1,gr=[globalRot[0]*gk,globalRot[1]*gk,globalRot[2]*gk],br=1+(breathe-1)*gk;
            var model=anchor?mul(T(driftX*gk+pos[0]*free,floatY*gk+pos[1]*free,pos[2]*free),mul(M([0,0,0],gr,[br,br,br]),M([pos[0]*e,pos[1]*e,pos[2]*e],rot,[sc,sc,sc])))
                :mul(M([driftX,floatY,0],globalRot,[breathe,breathe,breathe]),M(pos,rot,[sc,sc,sc]));
            //  구멍(퍼즐판 제외)에 다 들어간 조각은 입구 모양 스텐실로 잘라 옆면을 숨김 → 구멍 안에 끼워진 것처럼
            var clip=ghost&&ghost!=='board'&&p.p>.97;
            if(clip){gl.enable(gl.STENCIL_TEST);gl.stencilFunc(gl.EQUAL,1,255);gl.stencilOp(gl.KEEP,gl.KEEP,gl.KEEP);}
            draw(p.mesh,mul(place,model),colors[i],[p.t[0],p.t[1]]);
            if(clip)gl.disable(gl.STENCIL_TEST);
        });
        requestAnimationFrame(render);
    }
    //  13. 로더 종료 후 장면 시작 상태를 한 번만 설정
    function beginScene(){if(started!==Infinity)return;started=performance.now();setStage(-1);}
    //  초기 단계 설정 및 렌더 루프 즉시 시작; 로더 이벤트 또는 12초 fallback으로 beginScene 호출
    if(analysisMode){
        setAnalysisStage(canvas.dataset.analysisStage);
        analysisObserver=new MutationObserver(function(){setAnalysisStage(canvas.dataset.analysisStage);});
        analysisObserver.observe(canvas,{attributes:true,attributeFilter:['data-analysis-stage']});
        requestAnimationFrame(render);
    }else if(resultMode){
        setStage(-1);
        resultTimer=window.setInterval(function(){
            if(document.hidden)return;
            setStage(stage>=5?-1:stage+1);
        },1050);
        requestAnimationFrame(render);
    }else{
        setStage(-1); requestAnimationFrame(render);
        if(window.__jobPuzzleLoaderFinished) beginScene();
        else {document.addEventListener('jobpuzzle:loaderclosed',beginScene,{once:true});window.setTimeout(beginScene,12000);}
    }
    }

    function scanPuzzleScenes(){
        Array.prototype.forEach.call(
            document.querySelectorAll('#jobPuzzleCanvas, [data-job-puzzle-scene]'),
            mountPuzzleScene
        );
    }
    scanPuzzleScenes();
    if(window.MutationObserver&&document.body){
        var sceneObserver=new MutationObserver(scanPuzzleScenes);
        sceneObserver.observe(document.body,{childList:true,subtree:true});
    }
})();

