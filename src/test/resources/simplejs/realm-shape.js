const roots = [
  ['globalThis', globalThis],
  ['%ArrayIteratorPrototype%', Object.getPrototypeOf([][Symbol.iterator]())],
  ['%StringIteratorPrototype%', Object.getPrototypeOf(''[Symbol.iterator]())],
  ['%MapIteratorPrototype%', Object.getPrototypeOf(new Map()[Symbol.iterator]())],
  ['%SetIteratorPrototype%', Object.getPrototypeOf(new Set()[Symbol.iterator]())],
  ['%RegExpStringIteratorPrototype%', Object.getPrototypeOf('a'.matchAll(/a/g))],
  ['%GeneratorFunction%', Object.getPrototypeOf(function* () {}).constructor],
  ['%AsyncFunction%', Object.getPrototypeOf(async function () {}).constructor],
  ['%AsyncGeneratorFunction%', Object.getPrototypeOf(async function* () {}).constructor],
  ['%TypedArray%', Object.getPrototypeOf(Int8Array)],
  ['%IteratorHelperPrototype%', Object.getPrototypeOf([].values().map(x => x))],
  ['%WrapForValidIteratorPrototype%', Object.getPrototypeOf(Iterator.from({ next() { return { done: true }; } }))],
  ['%ThrowTypeError%', Object.getOwnPropertyDescriptor(Function.prototype, 'caller').get],
];
const names = new Map();
const queue = [];
const visit = (value, path) => {
  if (value === null || (typeof value !== 'object' && typeof value !== 'function')) {
    return null;
  }
  if (!names.has(value)) {
    names.set(value, path);
    queue.push(value);
  }
  return names.get(value);
};
const keyText = key => typeof key === 'symbol' ? '[' + String(key) + ']' : key;
const describe = (value, path) => {
  if (value === null) {
    return 'null';
  }
  if (typeof value === 'object' || typeof value === 'function') {
    return typeof value + ' -> ' + visit(value, path);
  }
  if (typeof value === 'number' && Object.is(value, -0)) {
    return 'number -0';
  }
  return typeof value + ' ' + String(value);
};
for (const [name, value] of roots) {
  visit(value, name);
}
const lines = [];
for (let i = 0; i < queue.length; i++) {
  const object = queue[i];
  const path = names.get(object);
  const proto = Object.getPrototypeOf(object);
  lines.push('== ' + path + ' : ' + typeof object + ' proto=' + describe(proto, path + '.[[Prototype]]')
    + (Object.isExtensible(object) ? '' : ' sealed'));
  for (const key of Reflect.ownKeys(object)) {
    const d = Object.getOwnPropertyDescriptor(object, key);
    const child = path + '.' + keyText(key);
    const flags = (d.enumerable ? 'e' : '-') + (d.configurable ? 'c' : '-');
    if ('value' in d) {
      lines.push('  ' + keyText(key) + ' ' + (d.writable ? 'w' : '-') + flags + ' ' + describe(d.value, child));
    } else {
      lines.push('  ' + keyText(key) + ' a' + flags + ' get=' + describe(d.get, child + '[get]')
        + ' set=' + describe(d.set, child + '[set]'));
    }
  }
}
return lines.join('\n');
