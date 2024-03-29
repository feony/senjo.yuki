/* Copyright 2017-2019, Senjo Org. Denis Rezvyakov aka Dinya Feony Senjo.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package org.senjo.conveyor;

import static org.senjo.basis.Base.Illegal;
import static org.senjo.conveyor.Father.father;

import java.util.Queue;
import org.senjo.annotation.*;
import org.senjo.basis.*;
import org.senjo.conveyor.Entry.*;
import org.senjo.support.Log;
import org.senjo.support.Config;

/** Абстрактная сущность конвейера, многопоточный исполнитель чего-либо — бесконечно
 * зацикленное множество потоков исполняющих одну или последовательность предопределённых
 * или поступающих задач.
 * 
 * @author Denis Rezvyakov aka Dinya Feony Senjo
 * @version create 2017-10, change 2019-03-14, beta */
public abstract class AConveyor extends ABasketSync {
	@NotNull public  final String      name ;
	@NotNull         final Log         log  ;
	/** Очередь задач конвейера назначенных на исполнение, спящие задачи отсутствуют. */
	@NotNull private final Queue<Unit> queue;
	/** Объект наблюдения и управления таймерами задач, прикреплённых к этому конвейеру. */
	@NotNull private final TimeKeeper  timer;

//XXX Реализовать хранение и обработку иерархии зависимостей конвейеров друг от друга
//	LinkedList<AConveyor> order;

	AConveyor(String name, @NotNull Queue<Unit> queue, @Nullable Log log) {
		push(Idle);
		this.name  = name ;
		this.queue = queue;
		this.log   = log != null ? log : Config.log("conveyor");
		this.timer = new TimeKeeper(this, Config.log("conveyor.timer"));
		father.add(this); }

	String kindEx() {
		return (this  instanceof MultiConveyor ? "Multi" : "")
		     + (queue instanceof PoorQueue     ? "SoloTask «" : "Conveyor «"); }



//======== Lines : методы управления исполнительными линиями конвейера ===================//
	/** Назначить одну из свободных линий для обработки указанной задачи. Метод сам меняет
	 * флаги {@link #Idle} и {@link #Load}. Если из свободных осталась только гибридная
	 * линия, то она отзывается от хранителя времени и ей назначается переданная задача.
	 * <p/>Важно! Данный метод нельзя вызывать, уже если стоит флаг {@link #Load}.
	 * @param plan — задача, которую нужно назначить пробуждаемой линии.
	 * @return Количество ещё свободных линий, используется базой чтобы правильно
	 *         установить состояние флага {@link #Load} — ставит если возвращается нуль.
	 * @throws ConveyorException — свободных линий в системе больше нет. */
	@Naive abstract void wakeup(@NotNull Unit plan) throws ConveyorException;

	/** Вернуть освободившуюся линию наследнику. Наследник пометит её как свободную, при
	 * необходимости он сам назначит её гибридной для обслуживания хранителя времени.
	 * Метод сам меняет флаги {@link #Idle} и {@link #Load}. Если линия не станет гибридной,
	 * то ей будет назначена задача {@code null} и она уйдёт в бесконечную спячку.
	 * @return следующая задача, которую должна обслуживать линия, обычно это null, иногда
	 *         хранитель времени. */
	@Naive abstract @Nullable Unit asleep(@NotNull Line line);

	/** Выдать гибридную конвейерную линию. Метод не проверяет никаких флагов, поднимает
	 * флаг {@link #Hybrid}, создаёт если нужно гибридную линию и возвращает её.
	 * <p/>Важно! {@link #Load} должен отсутствовать, это не проверяется. */
	@Naive abstract @NotNull Line hybrid();

	/** Создать новую исполнительную линию. При создании линии сразу выдаётся задача
	 * для обрабоки, и она запускается на исполнение. Если указана задача null, то линия
	 * после запуска сразу запаркуется. */
	@Naive final Line createLine(@Nullable Unit plan, int priority) {
		Line line = new Line(this, name);
		line.setPriority(priority);
		line.plan = plan; line.start();
		return line; }

	/** Установить указанный приоритет исполнения потока всем линиям конвейера. */
	@Synchronized public abstract AConveyor priority(int priority);

	/** Установить указанный приоритет исполнения потока всем линиям конвейера. */
	@Synchronized public final void priority(int value, boolean relative) {
		if (relative) {
			if      (value >  2) value =  2;
			else if (value < -2) value = -2;
			priority(Thread.NORM_PRIORITY + value);
		} else priority(value);
	}



//======== Plan : методы управления задачами =============================================//
	/** Добавить ещё одну задачу в конвейер на исполнение. Если все исполнительные линии
	 * заняты, то задача будет добавлена в очередь и выполнена позже.
	 * @param plan — абстрактная задача, которую конвейер должен выполнить. */
	@Synchronized void push(Unit plan) { try { sync();
		if (log.isDebug()) log.debugEx("Push ").hashName(plan).end();
		if (exist(Load)) queue.add(plan); else wakeup(plan);
	} finally { unsync(); } }

	/** Добавить указанное множество задач в конвейер на исполнение. Если все исполнительные
	 * линии заняты, то задача будет добавлена в очередь и выполнена позже.
	 * @param array — массив содержащий задачи для добавления в очередь конвейера;
	 * @param count — число элементов массива начиная с нулевого, которые нужно обслужить. */
	@Synchronized void push(Unit[] array, int count) { try { sync();
		Log.Buffer buffer = log.isDebug() ? log.debugEx("Push set [") : null;
		for (int index = 0; index != count; ++index) {
			Unit unit = array[index];
			if (buffer != null) buffer.div(',', ' ').hashName(unit);
			if (exist(Load)) queue.add(unit); else wakeup(unit); }
		if (buffer != null) buffer.add("], size=").add(count).end();
	} finally { unsync(); } }


	/** Обменивает текущий plan со следующим, который нужно обработать. Если текущий plan
	 * не нужно возвращать в очередь, то нужно передать null. Если передан plan и очередь
	 * пуста, возвращает его же; иначе возвращает первый элемент из очереди, а переданный
	 * кладёт в конец очереди. Если передан null и очередь пуста, то возвращает null. Если
	 * возвращается null, то данная линия уже помечена спящей, null — это безусловная
	 * команда уснуть для линии.<p/>
	 * Только этот метод снимает линии с исполнения заданий. Он же уведомляет Father,
	 * когда это нужно. */
	@Synchronized final Unit swap(@Nullable Unit plan) { try { sync();
		/* Сначала проконтролировать таймеры, если это требуется. Если очередь не пуста,
		 * то проверка однозначно требуется, т.к. конвейер ещё будет перегружен, а задачи
		 * должны поступать в порядке очереди. Если очередь пуста, то возможно уже наступил
		 * таймер и следует обработать разбуженную задачу, а не переданную в аргументе. */
		if (every(KeepActive)) checkTimer();

		// Обменяться задачами с очередью, подготовить и вернуть результат
		Unit poll = queue.poll();
		if (poll != null) { // Очередь не пуста, вернуть задачу из очереди, положить текущую
			if (plan != null) queue.offer(plan);
			return poll;
		} else { // Очередь пуста, вернуть plan если он передан, иначе усыпить линию
			// Если выполнение текущей задачи нужно продолжить, то сразу вернуть её обратно
			if (plan != null) return plan;
			// Иначе задач больше нет, усыпить линию, возможно она назначится хранителю
			plan = asleep(Line.current());
			if (every(Idle|Shutdown)) father.ready();
			return plan;
		}
	} catch (Father.Trap trap) { log.trace("Father's trap for kill conveyors");
	} finally { unsync(); } father.doKill(); return Line.kill; }

	final void assertShutdown() {
		if (empty(Finished)) father.unready();
		else throw ConveyorException.FailedWakeupBecauseShutdowned(); }



//======== Timer : методы работы с очередью таймеров и их срабатыванием ==================//
	/** Момент срабатывания самого ближайшего таймера в epoch. Задан только если все линии
	 * заняты пользовательскими задачами и конвейер сам должен поглядывать за таймерами. */
	long nextTimer;

	/** Добавить таймер задачи на ожидание. Когда момент времени таймера наступит, он
	 * автоматически будет разбужен. */
	<T extends Waiting> T append(T wait) { timer.push(wait); return wait; }

	/** Отозвать ранее добавленный, но ещё не сработавший таймер задачи. Когда момент
	 * времени таймера наступает, таймер автоматически выталкивается из очереди. */
	boolean remove(Waiting wait) { return timer.take(wait); }

	/** Отдать освободившуюся линию хранителю времени если это ему она вообще нужна.
	 * <p/>Важно! {@link #Hybrid} должен отсутствовать, это не проверяется. */
	@Naive Unit hybridInvoke(Line line) {
		if (clearTimer()) { timer.invoke(line); return timer; }
		else return null; }

	/** Забрать линию у хранителя времени, назначить линии пользовательскую задачу,
	 * активировать собственную проверку таймеров если она вообще нужна.
	 * <p/>Важно! {@link #Hybrid} должен присутствовать, это не проверяется. */
	@Naive void hybridRevoke(@NotNull Unit plan) {
		long wakeup = timer.revoke(plan); take(Hybrid);
		if (wakeup > 0) updateTimer(wakeup); }

	@Naive private boolean clearTimer() {
		switch (mask(mKeep)) {
		case KeepActive: swap(KeepActive, Hybrid); nextTimer = 0L; return true;
		case KeepLock  : turn(mKeep, KeepConfus); return false;
		default: return false; }
	}

	@Naive private void updateTimer(long wakeup) {
		switch (mask(mKeep)) {
		case KeepNone  : push(KeepActive); //nobreak;
		case KeepActive: nextTimer = wakeup; break;
		case KeepLock  : turn(mKeep, KeepConfus); break;
		case KeepConfus: break;
		default: throw Illegal(this, mKeep); }
	}

	/** Переключить таймер — вызывает только хранитель времени. В хранитель времени поступил
	 * новый таймер ожидания, возможно первый. При этом гарантируется, что хранителю
	 * не назначена гибридная линия. По возможности выдать хранителю времени исполнительную
	 * гибридную линию. Если же все линии заняты, то включить в конвейере режим
	 * периодической проверки таймера.
	 * @param timer — ожидаемый момент времени; 0 — ожидание больше не нужно;
	 * @return свободная исполнительная линия для хранителя времени. */
	@Lock(outer=TimeKeeper.class)
	@Synchronized final Line switchTimer(long timer) { try { sync();
		/* Если у конвейера есть свободная линия, выдать её хранителю если нужно, иначе
		 * обновить или удалить таймер. */
		if (empty(Load)) return timer > 0 ? hybrid() : null;
		else if (timer > 0) updateTimer(timer); else clearTimer();
		return null;
	} finally { unsync(); } }

	/** Проверить время срабатывания таймера, если время наступило, то обработать его
	 * через хранитель времени. Метод можно вызвать с синхронизацией и только если флаг
	 * {@link #SelfKeeper} поднят. */
	@Looper private final void checkTimer() {
		timer.log.debug("conveyor: Checking timers");
		long now = System.currentTimeMillis();
		if (now < this.nextTimer) return;

		turn(mKeep, KeepLock);
		long newTimer;
		/* Вынуждены снять синхронизацию по причинам:
		 * 1. операция проверки очереди таймеров может быть длительной;
		 * 2. таймеры возвращают задачи обратно в очередь, т.е. будет deadlock. */
		try { unsync(); newTimer = timer.apply(now); } finally { sync(); }
		switch (mask(mKeep)) {
		case KeepConfus: /* Редкая ситуация. Произошла какая-то неопределённость:
			 * 1. Метод вернул новое время ожидания, но перед блокировкой другой метод тоже
			 *    передал новое время. Это неопределённость, нужно в блокировке конвейера
			 *    заново запросить у хранителя времени актуальное время ожидания;
			 * 2. Пока хранитель обрабатывал таймеры освободилась конвейерная линия.
			 *    В режиме KeepLock конвейер не может назначить линию хранителю, потому
			 *    по возвращении нужно перепроверить флаг Load и выдать линию, если можно.*/
			timer.log.trace("Conveyor keeper confus is processing");
			newTimer = timer.nextWakeup();
			if (newTimer > 0 && empty(Load)) { newTimer = 0;
				Line line = hybrid(); timer.invoke(line); line.unpark(timer); }
			//nobreak;
		case KeepLock: // С блокировкой таймеров всё в порядке, обновляем время и снимаем её
			turn(mKeep, newTimer > 0 ? KeepActive : KeepNone);
			this.nextTimer = newTimer;
			break;
		default: throw Illegal(this, mKeep); }
	}

/*XXX Наряду с таймерами нужно создать Timeout'ы. Это время ликвидации пишется в саму
 * задачу, а задача помещается в отдельную очередь. Если Timeout наступает, то в задачу
 * подаётся соответствующий сигнал. Либо Timeout'ы можно сделать через таймеры. Например,
 * сделать такой Waitable. Все таймауты можно группировать в блоки, которые будут
 * срабатывать, например, раз в две секунды. При срабатывании все элементы блока получают
 * ошибку Timeout. Добавлять и удалять в блок можно через HashMap.
 *XXX В задаче сделать метод state(), чтобы можно было получить ожидающееся рабочее
 * состояние на Repeat или Await.
 *XXX Все отложенные задачи нужно хранить в отдельном множестве, т.к. при команде Shutdown
 * они все должны будут получить уведомление о завершении своей работы. Можно сделать три
 * автоматических режима завершения задачи (три поведения):
 * 1. задача сразу после обработки сигнала Shutdown прекращает своё выполнение;
 * 2. задача продолжает свою работу по обработке сигналов, но будет отключена, когда все
 *    задачи закончат свою работу;
 * 3. задача после сигнала Shutdown продолжает свою работу, пока сама не даст сигнал Finish.
 */



//======== Plan Hash : хранилище задач, которые пока ещё привязаны к конвейеру ===========//
	/** Команда завершения работы конвейера. Перед остановкой конвейер выполнит все задачи,
	 * которые ещё есть в очереди задач. После пошлёт сигнал в Father и дождётся от него
	 * команды на уничтожение конвейера. */
	@Synchronized public void shutdown() { try { sync();
		if (!push(Shutdown)) return;
//XXX Пока не отправляю сигнал задачам, в будущем нужно реализовать информирование задач
//		for (Plan plan : hash) plan.handle(Plan.$Shutdown);

		if (exist(Idle)) father.ready();
		return;
	} catch (Father.Trap trap) { log.trace("Father's trap for kill conveyors");
	} finally { unsync(); } father.doKill(); }

	@Synchronized public final boolean isShutdown() { return existSync(Shutdown); }

	/** Уничтожить конвейер, системный метод. Установить флаг {@link #Finished}, разбудить
	 * и завершить все спящие линии. Линия завершится сама из-за активного флага Finished.
	 * После выполнения данного метода любые задачи привязанные к данному конвейеру
	 * выполняться не будут. */
	@Synchronized final void kill() { try { sync();
		if (empty(Idle)) throw Illegal("Can't destroy the conveyor, it has a load line");
		father.remove(this);
		take(Shutdown);
		do wakeup(Line.kill); while (empty(Load));
	} finally { push(Shutdown|Finished); unsync(); } }

//	private HashSet<Plan> hash = new HashSet<>(); Пока не отправляю сигнал задачам
//
//	/** Была создана задача, привязанная к данному конвейеру. */
//	private void planCreated(Plan plan) {
//		try { sync();
//			hash.add(plan);
////XXX Надо бы поменять append на prepend
//			if (exist(Shutdown) && plan.appendEntryAndQueue(Entry.newCall(Plan.$Shutdown), 0))
//				push(plan);
//		} finally { unsync(); } 
//	}
//
//	/** Была завершена задача, привязанная к данному конвейеру. */
//	private void planDeleted(Plan plan) {
//		try { sync(); hash.remove(plan); } finally { unsync(); } }



//======== Basket : Маски для корзинки фруктов ===========================================//
	protected static final int fin = ABasket.fin-7;
	/** Конвейер полностью бездействует — флаг контролируется наследником. Ни одна
	 * из линий не обрабатывает пользовательские задачи, конвейер ждёт поступления задач
	 * на исполнение. При этом одна из линий может быть назначена хранителю времени следить
	 * за таймерами, это особый гибридный режим работы конвейерной линии {@link #Hybrid}
	 * для {@link TimeKeeper}, который не считается нагрузкой. */
	static final int Idle       = 1<<fin+1;
	/** Все линии конвейера заняты — флаг контролируется наследником. Свободных конвейерных
	 * линий у наследника нет, в том числе отозвана и гибридная линия. В этом режиме
	 * запрещается запрашивать у наследника ещё одну линию, иначе будет ошибка. */
	static final int Load       = 1<<fin+2;
	/** Одна линия находится в гибридном режиме работы. Это значит, что формально
	 * она свободна, но фактически обслуживает внутренний механизм (хранитель времени).
	 * При необходимости она автоматически отзывается наследником для обработки
	 * пользовательской задачи. */
	static final int Hybrid     = 1<<fin+3;

	/*   0 |  0 |  0    = таймеров нет, хранитель выключен, часть линий выполняют задачи;
	 *   0 |  0 |Hybrid = одна линия обслуживает хранителя, часть линий выполняют задачи;
	 *   0 |Load|  0    = хранитель выключен, все линии выделены и выполняют задачи;
	 *   0 |Load|Hybrid = одна линия обслуживает хранителя, все остальные выполняют задачи;
	 * Idle|  0 |  0    = таймеров нет, хранитель выключен, задач нет, все линии свободны;
	 * Idle|  0 |Hybrid = одна линия обслуживает хранителя, задач нет, остальные линии спят;
	 * Idle|Load|  0    = невозможное состояние: задачи не выполняются, но все линии заняты;
	 * Idle|Load|Hybrid = единственная линия обслуживает хранителя, задачи не выполняются.*/

	/** Маска режима личного наблюдения за временем для хранителя времени. */
	protected static final int mKeep    = 3<<fin+4;
	/** Конвейер не наблюдает за временем для хранителя времени. Либо у хранителя нет
	 * таймеров, либо одна из конвейерных линий целиком отдана для этих целей. */
	private static final int KeepNone   = 0<<fin+4;
	/** Контроль времени заблокирован, идёт параллельная обработка. Одна из конвейерных
	 * линий обнаружила наступление момента пробуждения. Тогда она устанавливает данное
	 * состояние освобождает конвейер и временно передаётся хранителю для пробуждения
	 * наступивших таймеров. Пока линия обрабатывает таймеры, конвейер находится в данном
	 * состоянии и не должен проверять или менять время срабатывания ближайшего таймера.
	 * В случае попытки поменять время параллельным потоком устанавливается состояние
	 * {@link #KeepConfus}. */
	private static final int KeepLock   = 1<<fin+4;
	/** Контроль времени заблокирован, идёт параллельная обработка, также есть факт
	 * неопределённости. Если во время блокировки для обработки момента пробуждения
	 * хранителем времени была предпринята попытка установить новое время ожидания
	 * параллельной линией, то время не ставится, но поднимается этот флаг. Это значит,
	 * что когда текущая линия вернёт управление конвейеру, а с ним и новый момент ожидания,
	 * то только один из двух (или более) моментов времени достоверный, и неясно какой
	 * именно. В этом случае в режиме блокировки конвейер должен будет просто заново
	 * запросить у хранителя текущее время ожидания. */
	private static final int KeepConfus = 2<<fin+4;
	/** Конвейер сам контролирует время для хранителя времени. Это значит все конвейерные
	 * линии заняты и конвейер сам после выполнения каждой задачи сравнивает текущее время
	 * с ближайшим ожидаемым. В случае наступления времени таймера включается обработка.<p/>
	 * HACK: данный флаг может проверяться через every, потому состоит только из единиц! */
	private static final int KeepActive = mKeep;

	/** Активирован режим полной остановки конвейера. Когда конвейер завершит обработку
	 * всех задач, он отправит сигнал в Father. Если после сигнала, но до ликвидации,
	 * задача поступит в очередь, то конвейер временно отзовёт свой сигнал. Father в нужный
	 * момент вызовет метод {@link #kill(boolean)}, чем заблокирует дальшейшую работу
	 * данного конвейера. Если остановка конвейера вызвана завершением работы приложения,
	 * то Father будет ждать момента опустошения всех конвейеров, иначе #kill будет вызван
	 * сразу после опустошения.
	 * <p/>Для справки: конвейер до полной остановки продолжает принимать задачи в свою
	 * очередь, т.к. существующие в очереди задачи могут в неё возвращаться, а также могут
	 * порождать важные подзадачи, в том числе и через другие конвейеры. */
	static final int Shutdown   = 1<<fin+6;
	/** Конвейер завершил свою работу. Больше задачи не могут поступать в очередь
	 * на исполнение. */
	static final int Finished   = 1<<fin+7;



	final int queueSize() { return queue.size     (); }
	final int timerSize() { return timer.queueSize(); }
	final boolean is(int mask) { return existSync(mask); }
}


/* Я вернул управление Idle, Load, а также частичный контроль за Hybrid в наследники.
 * Контролировать всё это в базисе без информации, которой обладают наследники, оказалось
 * сложно и запутанно. Последняя капля: если в Swap задач больше нет и был Load, то с одной
 * стороны нужно сделать Hybrid (отдать линию хранителю), а с другой, если это последняя
 * линия, то поставить Idle и дать сигнал Father'у. У простого конвейера только одна линия,
 * но знает об этом только наследник. Конечно, можно добавить такой флаг, а это будет ещё
 * одно ненужное нагромождение флагов и проверок, которые наследнику даже не понадобятся...
 */


