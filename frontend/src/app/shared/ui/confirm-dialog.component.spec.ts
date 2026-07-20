import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ConfirmDialogComponent } from './confirm-dialog.component';
import { ConfirmDialogService } from './confirm-dialog.service';
import { attachToBody, axeViolations, detachFromBody } from '../../../testing/axe';

describe('ConfirmDialogComponent', () => {
  let fixture: ComponentFixture<ConfirmDialogComponent>;
  let el: HTMLElement;
  let svc: ConfirmDialogService;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [ConfirmDialogComponent] });
    svc = TestBed.inject(ConfirmDialogService);
    fixture = TestBed.createComponent(ConfirmDialogComponent);
    el = fixture.nativeElement as HTMLElement;
    attachToBody(el);
    fixture.detectChanges();
  });
  afterEach(() => detachFromBody(el));

  it('renders nothing when no request is active', () => {
    expect(el.querySelector('.cd-panel')).toBeNull();
  });

  it('renders an accessible modal when a request is set', () => {
    svc.confirm({ title: 'Delete candidate?', body: 'This cannot be undone.' });
    fixture.detectChanges();
    const panel = el.querySelector('.cd-panel');
    expect(panel?.getAttribute('role')).toBe('dialog');
    expect(panel?.getAttribute('aria-modal')).toBe('true');
    expect(el.querySelector('.cd-title')?.textContent).toContain('Delete candidate?');
  });

  it('confirm button resolves the request true', async () => {
    const p = svc.confirm({ title: 'Delete?' });
    fixture.detectChanges();
    (el.querySelector('.cd-confirm') as HTMLButtonElement).click();
    await expectAsync(p).toBeResolvedTo(true);
  });

  it('cancel button resolves the request false', async () => {
    const p = svc.confirm({ title: 'Delete?' });
    fixture.detectChanges();
    (el.querySelector('.cd-cancel') as HTMLButtonElement).click();
    await expectAsync(p).toBeResolvedTo(false);
  });

  it('has zero axe violations while open', async () => {
    svc.confirm({ title: 'Delete?', body: 'Sure?' });
    fixture.detectChanges();
    const v = await axeViolations(el);
    expect(v).withContext(v.map((x) => x.id).join(', ')).toEqual([]);
  });
});
