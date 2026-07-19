import { fakeAsync, tick, TestBed } from '@angular/core/testing';
import { ToastService } from './toast.service';

describe('ToastService', () => {
  let svc: ToastService;
  beforeEach(() => { TestBed.configureTestingModule({}); svc = TestBed.inject(ToastService); });

  it('adds a toast with the given kind and message', () => {
    svc.success('Saved');
    expect(svc.toasts().length).toBe(1);
    expect(svc.toasts()[0].kind).toBe('success');
    expect(svc.toasts()[0].message).toBe('Saved');
  });

  it('supports error and info kinds', () => {
    svc.error('Boom'); svc.info('FYI');
    expect(svc.toasts().map((t) => t.kind)).toEqual(['error', 'info']);
  });

  it('dismiss removes the toast by id', () => {
    const id = svc.success('Saved');
    svc.dismiss(id);
    expect(svc.toasts().length).toBe(0);
  });

  it('auto-dismisses after the delay', fakeAsync(() => {
    svc.success('Saved');
    expect(svc.toasts().length).toBe(1);
    tick(4000);
    expect(svc.toasts().length).toBe(0);
  }));
});
