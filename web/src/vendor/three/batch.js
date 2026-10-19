// The house and furniture never move. One draw per material is enough on a TV GPU.
// Original floor objects keep their world matrices for raycasting.
import * as THREE from 'three';
import { mergeGeometries } from 'three/addons/utils/BufferGeometryUtils.js';
export function batchStatic(root) {
  root.updateMatrixWorld(true);
  const result = new THREE.Group();
  const buckets = new Map();
  root.traverse(object => {
    if (!object.isMesh || !object.visible) return;
    const source = object.geometry.index ? object.geometry.toNonIndexed() : object.geometry.clone();
    const parts = Array.isArray(object.material)
      ? source.groups.map(g => ({ material: object.material[g.materialIndex], start: g.start, count: g.count }))
      : [{ material: object.material, start: 0, count: source.attributes.position.count }];
    for (const part of parts) {
      const geometry = new THREE.BufferGeometry();
      for (const [name, attribute] of Object.entries(source.attributes)) {
        const array = attribute.array.slice(part.start * attribute.itemSize, (part.start + part.count) * attribute.itemSize);
        geometry.setAttribute(name, new THREE.BufferAttribute(array, attribute.itemSize, attribute.normalized));
      }
      geometry.applyMatrix4(object.matrixWorld);
      const key = `${part.material.uuid}:${object.castShadow}:${object.receiveShadow}`;
      if (!buckets.has(key)) buckets.set(key, { material: part.material, geometries: [], cast: object.castShadow, receive: object.receiveShadow });
      buckets.get(key).geometries.push(geometry);
    }
    source.dispose();
  });
  for (const bucket of buckets.values()) {
    const geometry = bucket.geometries.length === 1 ? bucket.geometries[0] : mergeGeometries(bucket.geometries, false);
    if (!geometry) { for (const g of bucket.geometries) result.add(new THREE.Mesh(g, bucket.material)); continue; }
    const mesh = new THREE.Mesh(geometry, bucket.material);
    mesh.castShadow = bucket.cast; mesh.receiveShadow = bucket.receive;
    mesh.matrixAutoUpdate = false; result.add(mesh);
    if (bucket.geometries.length > 1) for (const g of bucket.geometries) g.dispose();
  }
  return result;
}
