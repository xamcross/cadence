import { TestBed } from '@angular/core/testing';
import { ConfirmDialogService } from './confirm-dialog.service';

describe('ConfirmDialogService', () => {
  let svc: ConfirmDialogService;
  beforeEach(() => { TestBed.configureTestingModule({}); svc = TestBed.inject(ConfirmDialogService); });

  it('confirm() sets the active request', () => {
    void svc.confirm({ title: 'Delete?' });
    expect(svc.request()?.title).toBe('Delete?');
  });

  it('respond(true) resolves the promise true and clears the request', async () => {
    const p = svc.confirm({ title: 'Delete?' });
    svc.respond(true);
    await expectAsync(p).toBeResolvedTo(true);
    expect(svc.request()).toBeNull();
  });

  it('respond(false) resolves false', async () => {
    const p = svc.confirm({ title: 'Delete?' });
    svc.respond(false);
    await expectAsync(p).toBeResolvedTo(false);
  });

  it('opening a second confirm resolves the first as false', async () => {
    const first = svc.confirm({ title: 'A' });
    const second = svc.confirm({ title: 'B' });
    await expectAsync(first).toBeResolvedTo(false);
    expect(svc.request()?.title).toBe('B');
    svc.respond(true);
    await expectAsync(second).toBeResolvedTo(true);
  });
});
